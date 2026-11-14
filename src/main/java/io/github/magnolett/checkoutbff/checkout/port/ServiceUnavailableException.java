package io.github.magnolett.checkoutbff.checkout.port;

/**
 * A dependency could not answer (timeout, connection error, 5xx, open circuit). This is a technical failure, not a
 * business one: the BFF answers 503 with the same error object the frontend already knows.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String service;

    public ServiceUnavailableException(String service, String detail, Throwable cause) {
        super(service + " unavailable: " + detail, cause);
        this.service = service;
    }

    public ServiceUnavailableException(String service, String detail) {
        this(service, detail, null);
    }

    public String service() {
        return service;
    }
}
