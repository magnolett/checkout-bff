package io.github.magnolett.checkoutbff.integration.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;

/**
 * Publishes payment events to Kafka. The key is the cart id, so the events of one cart land on the same partition
 * and keep their order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final KafkaTemplate<String, String> kafka;
    private final MessagingProperties properties;

    /**
     * Completes when the broker acknowledged the record (acks=all, idempotent producer). The send runs on a thread
     * that may block: the Kafka client can wait for metadata (max.block.ms), and that wait must never happen on an
     * event loop or on a Mongo driver thread.
     */
    public Mono<Void> publish(String cartId, String eventId, String eventType, String payload) {
        return Mono.fromFuture(() -> {
                    ProducerRecord<String, String> record =
                            new ProducerRecord<>(properties.paymentEventsTopic(), cartId, payload);
                    record.headers().add(EVENT_ID_HEADER, eventId.getBytes(StandardCharsets.UTF_8));
                    record.headers().add(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
                    return kafka.send(record);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(result -> log.info("event published cartId={} type={} partition={} offset={}", cartId,
                        eventType, result.getRecordMetadata().partition(), result.getRecordMetadata().offset()))
                .then();
    }
}
