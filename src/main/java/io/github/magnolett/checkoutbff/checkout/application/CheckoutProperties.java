package io.github.magnolett.checkoutbff.checkout.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Checkout rules that are configuration, not code.
 *
 * @param cartTtl         how long the price snapshot of a cart stays valid
 * @param paymentTimeout  how long the BFF waits for the gateway before giving up on an authorization
 * @param processingLease how long an attempt holds the cart. It must be longer than the payment timeout, so a second
 *                        request never reaches the gateway while the first one is still waiting for it
 */
@ConfigurationProperties("checkout")
public record CheckoutProperties(
        @DefaultValue("30m") Duration cartTtl,
        @DefaultValue("10s") Duration paymentTimeout,
        @DefaultValue("30s") Duration processingLease) {

    public CheckoutProperties {
        if (processingLease.compareTo(paymentTimeout) <= 0) {
            throw new IllegalArgumentException("checkout.processing-lease (" + processingLease
                    + ") must be longer than checkout.payment-timeout (" + paymentTimeout + ")");
        }
    }
}
