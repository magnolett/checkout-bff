package io.github.magnolett.checkoutbff.subscription;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * A failing event is retried twice, one second apart, then parked in the dead letter topic with the error in its
 * headers, and the partition moves on. A payload that does not parse goes straight there: retrying cannot fix it.
 *
 * <p>The dead letter topic belongs to this consumer, since it is the one that failed, so it is declared here. Its
 * name is explicit instead of the recoverer's default: Spring Kafka 4 changed that default from {@code .DLT} to
 * {@code -dlt}, and relying on it sent failed events to a topic nobody had created. The recoverer keeps the
 * partition of the failed record, so the dead letter topic has as many partitions as the source.
 */
@Configuration(proxyBeanMethods = false)
class SubscriptionKafkaConfig {

    static final String DEAD_LETTER_SUFFIX = ".DLT";

    @Bean
    NewTopic paymentEventsDeadLetterTopic(
            @Value("${messaging.payment-events-topic:checkout.payment-events}") String topic,
            @Value("${messaging.partitions:3}") int partitions) {
        return TopicBuilder.name(topic + DEAD_LETTER_SUFFIX).partitions(partitions).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
