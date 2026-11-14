package io.github.magnolett.checkoutbff.integration.messaging;

import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentApproved;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentDeclined;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published contract of the payment events topic. It is the only shape other services see, so domain classes can
 * change without breaking consumers. It never carries card data.
 */
public record PaymentEventMessage(
        int schemaVersion,
        UUID eventId,
        String eventType,
        UUID cartId,
        Instant occurredAt,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        List<String> productSlugs,
        String transactionId,
        String declineReason) {

    public static final int SCHEMA_VERSION = 1;
    public static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";
    public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";

    public static PaymentEventMessage from(CheckoutEvent event) {
        return switch (event) {
            case PaymentApproved approved -> new PaymentEventMessage(SCHEMA_VERSION, approved.eventId(),
                    PAYMENT_APPROVED, approved.cartId(), approved.occurredAt(), approved.methodType().name(),
                    approved.amount().amount(), approved.amount().currency(), approved.productSlugs(),
                    approved.transactionId(), null);
            case PaymentDeclined declined -> new PaymentEventMessage(SCHEMA_VERSION, declined.eventId(),
                    PAYMENT_DECLINED, declined.cartId(), declined.occurredAt(), declined.methodType().name(),
                    declined.amount().amount(), declined.amount().currency(), List.of(), null, declined.reason());
        };
    }
}
