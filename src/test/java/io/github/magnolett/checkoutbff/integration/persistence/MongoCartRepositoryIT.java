package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentOrder;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.ConcurrentCartUpdateException;
import io.github.magnolett.checkoutbff.support.IntegrationTestBase;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The relay is off in this context, so the outbox can be inspected right after each write. */
@TestPropertySource(properties = "messaging.outbox.enabled=false")
class MongoCartRepositoryIT extends IntegrationTestBase {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private MongoCartRepository repository;

    @Autowired
    private ReactiveMongoTemplate mongo;

    @Test
    void roundTripKeepsMoneyScaleTimesAndSnapshot() {
        Cart cart = repository.insert(newCart()).block();

        Cart read = repository.findById(cart.id()).block();

        assertThat(read.total()).isEqualTo(Money.of("39.90", "BRL"));
        assertThat(read.items()).containsExactlyElementsOf(cart.items());
        assertThat(read.paymentMethods()).containsExactlyElementsOf(cart.paymentMethods());
        assertThat(read.expiresAt()).isEqualTo(cart.expiresAt());
        assertThat(read.version()).isZero();
        Document raw = raw(cart);
        assertThat(raw.get("total")).isInstanceOf(Decimal128.class);
        assertThat(raw.getDate("purgeAt")).isNotNull();
    }

    @Test
    void stateChangeAndItsEventAreOneWrite() {
        Cart cart = repository.insert(newCart()).block();
        Instant now = now();
        PendingPayment pending = cart.startPayment(new PaymentOrder("Pix", null, null), now, LEASE);
        repository.update(cart).block();
        cart.approve(pending.attemptId(), "E99999999202611141600ABCDEFGHIJK", now);

        repository.update(cart).block();

        Document raw = raw(cart);
        assertThat(raw.getString("status")).isEqualTo("PAID");
        assertThat(((Number) raw.get("version")).longValue()).isEqualTo(2L);
        assertThat(raw.containsKey("purgeAt")).as("a paid cart is never purged").isFalse();
        List<Document> outbox = raw.getList("outbox", Document.class);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst().getString("eventType")).isEqualTo("PAYMENT_APPROVED");
        assertThat(outbox.getFirst().getString("payload")).contains(cart.id().toString(), "\"PIX\"");
        assertThat(cart.newEvents()).as("events are cleared once persisted").isEmpty();
    }

    @Test
    void staleVersionLosesTheRace() {
        UUID id = repository.insert(newCart()).block().id();
        Cart first = repository.findById(id).block();
        Cart second = repository.findById(id).block();
        first.startPayment(cardOrder(), now(), LEASE);
        second.startPayment(cardOrder(), now(), LEASE);

        repository.update(first).block();

        StepVerifier.create(repository.update(second)).expectError(ConcurrentCartUpdateException.class).verify();
        assertThat(repository.findById(id).block().attempts()).hasSize(1);
    }

    @Test
    void neverStoresTheFullCardNumberOrTheCvv() {
        Cart cart = repository.insert(newCart()).block();
        cart.startPayment(cardOrder(), now(), LEASE);

        repository.update(cart).block();

        Document raw = raw(cart);
        assertThat(raw.toJson()).doesNotContain("4242424242424242");
        Document attempt = raw.getList("attempts", Document.class).getFirst();
        assertThat(attempt.getString("lastDigits")).isEqualTo("4242");
        assertThat(attempt.keySet()).doesNotContain("number", "cvv", "card", "holderName");
    }

    @Test
    void indexesForPurgeAndOutboxExist() {
        List<IndexInfo> indexes = mongo.indexOps(CartDocument.COLLECTION).getIndexInfo().collectList().block();

        IndexInfo ttl = indexes.stream().filter(i -> i.getName().equals("carts_purge_ttl")).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
        IndexInfo outbox = indexes.stream().filter(i -> i.getName().equals("carts_outbox_waiting")).findFirst()
                .orElseThrow();
        assertThat(outbox.getPartialFilterExpression()).contains("outbox.0");
    }

    private Document raw(Cart cart) {
        return mongo.findById(cart.id().toString(), Document.class, CartDocument.COLLECTION).block();
    }

    private static Cart newCart() {
        return Cart.create(UUID.randomUUID(),
                List.of(new Product("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
                        Money.of("39.90", "BRL"))),
                List.of(new PaymentMethod(PaymentMethodType.CREDIT_CARD, "Cartão de Crédito"),
                        new PaymentMethod(PaymentMethodType.PIX, "Pix")),
                now(), Duration.ofMinutes(30));
    }

    private static PaymentOrder cardOrder() {
        return new PaymentOrder("Cartão de Crédito", new CardData("Ana Souza", "4242424242424242", "12/30", "123"),
                null);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
