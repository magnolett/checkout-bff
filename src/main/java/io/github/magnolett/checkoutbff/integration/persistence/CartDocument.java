package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.checkout.domain.AttemptStatus;
import io.github.magnolett.checkoutbff.checkout.domain.CartStatus;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Storage shape of a cart. One document holds the whole aggregate, its attempts and the events still waiting to be
 * published (outbox). One document means one atomic write: state and events never diverge, and no multi-document
 * transaction is needed.
 *
 * <p>Card data is limited to the last digits. The full number and the CVV exist only in memory during the request.
 */
@Document(CartDocument.COLLECTION)
record CartDocument(
        @Id String id,
        CartStatus status,
        List<ItemDocument> items,
        List<PaymentMethodDocument> paymentMethods,
        BigDecimal total,
        String currency,
        Instant createdAt,
        Instant expiresAt,
        Instant purgeAt,
        Instant processingLeaseUntil,
        Instant paidAt,
        List<AttemptDocument> attempts,
        long version,
        List<OutboxEntry> outbox,
        Instant outboxClaimedUntil) {

    static final String COLLECTION = "carts";

    record ItemDocument(String slug, String name, String description, BigDecimal price, String currency) {
    }

    record PaymentMethodDocument(PaymentMethodType type, String name) {
    }

    record AttemptDocument(String id, String idempotencyKey, String fingerprint, PaymentMethodType methodType,
                           String methodName, String lastDigits, BigDecimal amount, String currency,
                           AttemptStatus status, String transactionId, String declineReason, Instant startedAt,
                           Instant completedAt) {
    }
}
