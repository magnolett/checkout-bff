package io.github.magnolett.checkoutbff.checkout.port;

import java.util.UUID;

/** The cart changed between read and write (optimistic lock). Another request won the race. */
public class ConcurrentCartUpdateException extends RuntimeException {

    public ConcurrentCartUpdateException(UUID cartId) {
        super("cart " + cartId + " was changed by another request", null, false, false);
    }
}
