package io.github.magnolett.checkoutbff.integration.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Kafka topic of the payment events and the outbox relay that feeds it.
 *
 * @param paymentEventsTopic topic of the payment events, keyed by cart id
 * @param partitions         partitions of the topic; the consumer's dead letter topic uses the same number
 */
@ConfigurationProperties("messaging")
public record MessagingProperties(
        @DefaultValue("checkout.payment-events") String paymentEventsTopic,
        @DefaultValue("3") int partitions,
        @DefaultValue Outbox outbox) {

    /**
     * @param enabled       turns the relay off, for tests that do not need Kafka
     * @param relayInterval pause between two relay runs
     * @param batchSize     carts relayed per run, at most
     * @param claimDuration how long a relay instance owns a cart; after that another one may take it over
     */
    public record Outbox(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration relayInterval,
                         @DefaultValue("50") int batchSize, @DefaultValue("30s") Duration claimDuration) {
    }
}
