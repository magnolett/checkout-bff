package io.github.magnolett.checkoutbff.checkout.domain;

/**
 * Cart lifecycle. PAID is the only terminal state: a declined cart accepts a new attempt, because the error screen
 * offers "try again" and "change payment method".
 */
public enum CartStatus {
    OPEN,
    PROCESSING,
    PAID,
    DECLINED
}
