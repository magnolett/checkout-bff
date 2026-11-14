package io.github.magnolett.checkoutbff.checkout.domain;

import java.util.Objects;

/** A payment method offered in a cart: the stable type and the name shown to the customer. */
public record PaymentMethod(PaymentMethodType type, String name) {

    public PaymentMethod {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }
}
