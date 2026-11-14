package io.github.magnolett.checkoutbff.bff.error;

/** Request that violates the HTTP contract in a way Bean Validation does not express (e.g. repeated slugs). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String detail) {
        super(detail, null, false, false);
    }
}
