package io.github.magnolett.checkoutbff.checkout.domain;

import java.util.UUID;

/**
 * Payment ready to be sent to the gateway. The attempt id travels as the gateway idempotency key, so a resumed
 * attempt is never charged twice by a real gateway. The card exists here only in memory.
 */
public record PendingPayment(UUID cartId, UUID attemptId, PaymentMethodType methodType, Money amount, CardData card) {
}
