package io.github.magnolett.checkoutbff.subscription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Downstream consumer that activates the subscription when a payment is approved. It plays the role of another
 * service: it knows the topic and the JSON contract, never the checkout classes.
 *
 * <p>Delivery is at least once (the relay may publish twice after a crash, Kafka may redeliver after a rebalance),
 * so activation is idempotent: the subscription id is the cart id and the write is an upsert with $setOnInsert. A
 * repeated event changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SubscriptionActivationListener {

    static final String COLLECTION = "subscriptions";
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveMongoTemplate mongo;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Runs on a Kafka consumer thread, not on the event loop, so waiting for the write is the expected model: the
     * offset is committed only after the subscription is saved.
     */
    @KafkaListener(id = "subscription-activation", groupId = "subscription-activation",
            topics = "${messaging.payment-events-topic:checkout.payment-events}")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        PaymentEvent event = jsonMapper.readValue(record.value(), PaymentEvent.class);
        if (!PaymentEvent.APPROVED.equals(event.eventType())) {
            return;
        }
        Update activation = new Update()
                .setOnInsert("status", "ACTIVE")
                .setOnInsert("products", event.productSlugs())
                .setOnInsert("paymentMethod", event.paymentMethod())
                .setOnInsert("amount", event.amount())
                .setOnInsert("currency", event.currency())
                .setOnInsert("transactionId", event.transactionId())
                .setOnInsert("sourceEventId", event.eventId())
                .setOnInsert("activatedAt", clock.instant().truncatedTo(ChronoUnit.SECONDS));
        UpdateResult result = mongo.upsert(Query.query(Criteria.where("_id").is(event.cartId())), activation,
                        COLLECTION)
                .block(WRITE_TIMEOUT);
        if (result != null && result.getUpsertedId() != null) {
            log.info("subscription activated cartId={} products={}", event.cartId(), event.productSlugs());
        } else {
            log.info("repeated payment event ignored cartId={} eventId={}", event.cartId(), event.eventId());
        }
    }

    /** The consumer's own view of the event: only the fields it needs, tolerant to new ones. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentEvent(String eventId, String eventType, String cartId, String paymentMethod, BigDecimal amount,
                        String currency, List<String> productSlugs, String transactionId) {

        static final String APPROVED = "PAYMENT_APPROVED";
    }
}
