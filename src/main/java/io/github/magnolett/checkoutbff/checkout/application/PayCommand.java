package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentOrder;

import java.util.UUID;

/** Checkout request translated from the HTTP contract. The card is null for Pix. */
public record PayCommand(UUID cartId, String methodName, CardData card, String idempotencyKey) {

    public PaymentOrder order() {
        return new PaymentOrder(methodName, card, idempotencyKey);
    }
}
