package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CartStatus;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAttempt;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.integration.IntegrationProperties;
import io.github.magnolett.checkoutbff.integration.messaging.PaymentEventMessage;
import io.github.magnolett.checkoutbff.integration.persistence.CartDocument.AttemptDocument;
import io.github.magnolett.checkoutbff.integration.persistence.CartDocument.ItemDocument;
import io.github.magnolett.checkoutbff.integration.persistence.CartDocument.PaymentMethodDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Translation between the cart aggregate and its document. The domain never sees the storage shape. */
@Component
@RequiredArgsConstructor
class CartDocumentMapper {

    private final JsonMapper jsonMapper;
    private final IntegrationProperties properties;

    CartDocument toDocument(Cart cart) {
        return new CartDocument(cart.id().toString(), cart.status(),
                cart.items().stream().map(CartDocumentMapper::toDocument).toList(),
                cart.paymentMethods().stream().map(m -> new PaymentMethodDocument(m.type(), m.name())).toList(),
                cart.total().amount(), cart.total().currency(), cart.createdAt(), cart.expiresAt(), purgeAt(cart),
                cart.processingLeaseUntil(), cart.paidAt(), attempts(cart), cart.version(), outbox(cart), null);
    }

    Cart toDomain(CartDocument document) {
        return Cart.restore(UUID.fromString(document.id()),
                document.items().stream().map(CartDocumentMapper::toProduct).toList(),
                document.paymentMethods().stream().map(m -> new PaymentMethod(m.type(), m.name())).toList(),
                new Money(document.total(), document.currency()),
                document.createdAt(), document.expiresAt(), document.status(), document.processingLeaseUntil(),
                document.attempts() == null ? List.of()
                        : document.attempts().stream().map(CartDocumentMapper::toAttempt).toList(),
                document.paidAt(), document.version());
    }

    List<AttemptDocument> attempts(Cart cart) {
        return cart.attempts().stream().map(CartDocumentMapper::toDocument).toList();
    }

    /** The events raised since the cart was read, already serialized in their published shape. */
    List<OutboxEntry> outbox(Cart cart) {
        return cart.newEvents().stream().map(this::toOutboxEntry).toList();
    }

    /** An unpaid cart is purged by a TTL index some time after it expires. A paid cart is the record of a sale. */
    Instant purgeAt(Cart cart) {
        return cart.status() == CartStatus.PAID
                ? null
                : cart.expiresAt().plus(properties.persistence().abandonedCartRetention());
    }

    private OutboxEntry toOutboxEntry(CheckoutEvent event) {
        PaymentEventMessage message = PaymentEventMessage.from(event);
        return new OutboxEntry(message.eventId().toString(), message.eventType(), message.occurredAt(),
                jsonMapper.writeValueAsString(message));
    }

    private static ItemDocument toDocument(Product product) {
        return new ItemDocument(product.slug(), product.name(), product.description(), product.price().amount(),
                product.price().currency());
    }

    private static Product toProduct(ItemDocument item) {
        return new Product(item.slug(), item.name(), item.description(), new Money(item.price(), item.currency()));
    }

    private static AttemptDocument toDocument(PaymentAttempt attempt) {
        return new AttemptDocument(attempt.id().toString(), attempt.idempotencyKeys(), attempt.fingerprint(),
                attempt.methodType(), attempt.methodName(), attempt.lastDigits(), attempt.amount().amount(),
                attempt.amount().currency(), attempt.status(), attempt.transactionId(), attempt.declineReason(),
                attempt.startedAt(), attempt.completedAt());
    }

    private static PaymentAttempt toAttempt(AttemptDocument document) {
        List<String> keys = document.idempotencyKeys() == null ? List.of() : document.idempotencyKeys();
        return new PaymentAttempt(UUID.fromString(document.id()), keys, document.fingerprint(),
                document.methodType(), document.methodName(), document.lastDigits(),
                new Money(document.amount(), document.currency()), document.status(), document.transactionId(),
                document.declineReason(), document.startedAt(), document.completedAt());
    }
}
