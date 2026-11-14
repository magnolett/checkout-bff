package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentOrder;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.integration.messaging.MessagingProperties;
import io.github.magnolett.checkoutbff.integration.messaging.PaymentEventPublisher;
import io.github.magnolett.checkoutbff.support.IntegrationTestBase;
import io.github.magnolett.checkoutbff.support.KafkaRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the relay by hand (the scheduled one is off here) to check ordering, removal and claims. */
@TestPropertySource(properties = "messaging.outbox.enabled=false")
class OutboxRelayIT extends IntegrationTestBase {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private MongoCartRepository repository;

    @Autowired
    private ReactiveMongoTemplate mongo;

    @Autowired
    private PaymentEventPublisher publisher;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private Clock clock;

    @Test
    void publishesInOrderKeyedByCartThenRemovesFromTheOutbox() {
        Cart cart = cartDeclinedThenPaid();

        relay().relayPendingEvents().block(Duration.ofSeconds(30));

        List<ConsumerRecord<String, String>> records = KafkaRecords.read(KAFKA.getBootstrapServers(),
                PAYMENT_EVENTS_TOPIC, cart.id().toString(), 2, Duration.ofSeconds(20));
        assertThat(records).hasSize(2);
        assertThat(records).extracting(record -> KafkaRecords.header(record, "eventType"))
                .containsExactly("PAYMENT_DECLINED", "PAYMENT_APPROVED");
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
        assertThat(records.get(1).value()).contains("\"schemaVersion\":1", "\"productSlugs\":[\"premium\"]")
                .doesNotContain("4000000000000002", "4242424242424242");
        Document raw = raw(cart);
        assertThat(raw.getList("outbox", Document.class)).isEmpty();
        assertThat(raw.containsKey("outboxClaimedUntil")).isFalse();
    }

    @Test
    void cartClaimedByAnotherInstanceIsLeftAloneUntilTheClaimExpires() {
        Cart cart = cartDeclinedThenPaid();
        Query byId = Query.query(Criteria.where("_id").is(cart.id().toString()));
        mongo.updateFirst(byId, Update.update("outboxClaimedUntil", clock.instant().plusSeconds(30)),
                CartDocument.COLLECTION).block();

        relay().relayPendingEvents().block(Duration.ofSeconds(30));
        assertThat(raw(cart).getList("outbox", Document.class)).hasSize(2);

        mongo.updateFirst(byId, Update.update("outboxClaimedUntil", clock.instant().minusSeconds(1)),
                CartDocument.COLLECTION).block();
        relay().relayPendingEvents().block(Duration.ofSeconds(30));
        assertThat(raw(cart).getList("outbox", Document.class)).isEmpty();
    }

    private OutboxRelay relay() {
        return new OutboxRelay(mongo, publisher, properties, clock);
    }

    /** A declined attempt and then an approved one: two events, in this order, in one cart. */
    private Cart cartDeclinedThenPaid() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Cart cart = repository.insert(Cart.create(UUID.randomUUID(),
                List.of(new Product("premium", "Plano Premium", null, Money.of("39.90", "BRL"))),
                List.of(new PaymentMethod(PaymentMethodType.CREDIT_CARD, "Cartão de Crédito")),
                now, Duration.ofMinutes(30))).block();
        PendingPayment declined = cart.startPayment(card("4000000000000002"), now, LEASE);
        repository.update(cart).block();
        cart.decline(declined.attemptId(), "declined by the issuer", now);
        repository.update(cart).block();
        PendingPayment approved = cart.startPayment(card("4242424242424242"), now, LEASE);
        repository.update(cart).block();
        cart.approve(approved.attemptId(), "SIM-" + approved.attemptId(), now);
        return repository.update(cart).block();
    }

    private static PaymentOrder card(String number) {
        return new PaymentOrder("Cartão de Crédito", new CardData("Ana Souza", number, "12/30", "123"), null);
    }

    private Document raw(Cart cart) {
        return mongo.findById(cart.id().toString(), Document.class, CartDocument.COLLECTION).block();
    }
}
