package io.github.magnolett.checkoutbff.integration.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The payment events topic, created at startup by Spring's KafkaAdmin. The replication factor is the broker default:
 * one in the local compose, three in a real cluster. Its dead letter topic is declared by the consumer that owns it.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    @Bean
    NewTopic paymentEventsTopic(MessagingProperties properties) {
        return TopicBuilder.name(properties.paymentEventsTopic()).partitions(properties.partitions()).build();
    }
}
