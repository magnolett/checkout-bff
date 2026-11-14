package io.github.magnolett.checkoutbff.subscription;

import io.github.magnolett.checkoutbff.support.IntegrationTestBase;
import io.github.magnolett.checkoutbff.support.KafkaRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The consumer as another service would see it: raw JSON on the topic, at-least-once delivery. */
@TestPropertySource(properties = "messaging.outbox.enabled=false")
class SubscriptionActivationIT extends IntegrationTestBase {

    private static final String GROUP = "subscription-activation";
    private static final String DEAD_LETTER_TOPIC = PAYMENT_EVENTS_TOPIC + SubscriptionKafkaConfig.DEAD_LETTER_SUFFIX;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ReactiveMongoTemplate mongo;

    @Test
    void repeatedApprovalActivatesTheSubscriptionOnceAndKeepsTheFirstEvent() throws Exception {
        String cartId = UUID.randomUUID().toString();
        String firstEventId = UUID.randomUUID().toString();

        send(cartId, approved(firstEventId, cartId));
        send(cartId, approved(firstEventId, cartId));
        RecordMetadata last = send(cartId, approved(UUID.randomUUID().toString(), cartId));
        KafkaRecords.awaitConsumed(KAFKA.getBootstrapServers(), GROUP, last, Duration.ofSeconds(30));

        assertThat(subscriptions(cartId)).singleElement().satisfies(subscription -> {
            assertThat(subscription.getString("status")).isEqualTo("ACTIVE");
            assertThat(subscription.getString("sourceEventId")).isEqualTo(firstEventId);
            assertThat(subscription.getList("products", String.class)).containsExactly("premium");
        });
        assertThat(KafkaRecords.read(KAFKA.getBootstrapServers(), DEAD_LETTER_TOPIC, cartId, 1, Duration.ofSeconds(2)))
                .as("a repeated event is not a failure").isEmpty();
    }

    @Test
    void declinedPaymentDoesNotActivateAnything() throws Exception {
        String cartId = UUID.randomUUID().toString();

        RecordMetadata sent = send(cartId, """
                {"eventId":"%s","eventType":"PAYMENT_DECLINED","cartId":"%s","productSlugs":[]}"""
                .formatted(UUID.randomUUID(), cartId));
        KafkaRecords.awaitConsumed(KAFKA.getBootstrapServers(), GROUP, sent, Duration.ofSeconds(30));

        assertThat(subscriptions(cartId)).isEmpty();
    }

    @Test
    void unreadableEventGoesToTheDeadLetterTopicOnTheSamePartition() throws Exception {
        String key = UUID.randomUUID().toString();

        RecordMetadata sent = send(key, "this is not json");

        List<ConsumerRecord<String, String>> parked = KafkaRecords.read(KAFKA.getBootstrapServers(),
                DEAD_LETTER_TOPIC, key, 1, Duration.ofSeconds(30));
        assertThat(parked).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualTo("this is not json");
            assertThat(record.partition()).isEqualTo(sent.partition());
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-fqcn")).isNotBlank();
            assertThat(KafkaRecords.header(record, "kafka_dlt-original-topic")).isEqualTo(PAYMENT_EVENTS_TOPIC);
        });
    }

    private RecordMetadata send(String key, String value) throws Exception {
        return kafka.send(PAYMENT_EVENTS_TOPIC, key, value).get().getRecordMetadata();
    }

    /** Has a field this consumer does not know, as a newer producer would send. */
    private static String approved(String eventId, String cartId) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"PAYMENT_APPROVED","cartId":"%s",\
                "occurredAt":"2026-11-14T16:00:00Z","paymentMethod":"PIX","amount":39.90,"currency":"BRL",\
                "productSlugs":["premium"],"transactionId":"E99999999202611141600ABCDEFGHIJK","declineReason":null,\
                "aFieldFromTheFuture":true}""".formatted(eventId, cartId);
    }

    private List<Document> subscriptions(String cartId) {
        return mongo.find(Query.query(Criteria.where("_id").is(cartId)), Document.class,
                SubscriptionActivationListener.COLLECTION).collectList().block(Duration.ofSeconds(5));
    }
}
