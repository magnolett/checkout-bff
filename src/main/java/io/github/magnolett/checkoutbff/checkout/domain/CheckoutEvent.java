package io.github.magnolett.checkoutbff.checkout.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain events raised by the cart. They are stored in the same atomic write as the state change (outbox) and
 * published afterwards, so an event never exists without its state change and vice versa.
 */
public sealed interface CheckoutEvent {

    UUID eventId();

    UUID cartId();

    Instant occurredAt();

    record PaymentApproved(UUID eventId, UUID cartId, Instant occurredAt, PaymentMethodType methodType, Money amount,
                           List<String> productSlugs, String transactionId) implements CheckoutEvent {
    }

    record PaymentDeclined(UUID eventId, UUID cartId, Instant occurredAt, PaymentMethodType methodType, Money amount,
                           String reason) implements CheckoutEvent {
    }
}
