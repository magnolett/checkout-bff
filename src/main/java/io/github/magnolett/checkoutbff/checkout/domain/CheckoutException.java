package io.github.magnolett.checkoutbff.checkout.domain;

import java.util.UUID;

/**
 * Expected business failure. It carries no stack trace: these failures are part of the flow, not bugs, and the
 * message never contains card data.
 */
public class CheckoutException extends RuntimeException {

    private final CheckoutError error;
    private final UUID cartId;

    public CheckoutException(CheckoutError error, UUID cartId, String detail) {
        super(detail == null ? error.name() : error.name() + ": " + detail, null, false, false);
        this.error = error;
        this.cartId = cartId;
    }

    public CheckoutException(CheckoutError error, UUID cartId) {
        this(error, cartId, null);
    }

    public CheckoutError error() {
        return error;
    }

    public UUID cartId() {
        return cartId;
    }
}
