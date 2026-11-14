package io.github.magnolett.checkoutbff.integration.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/** Short description of why a call to an external service failed, for logs and exception messages. */
final class Failures {

    private Failures() {
    }

    static String describe(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        if (error instanceof TimeoutException) {
            return "no answer in time";
        }
        if (error instanceof CallNotPermittedException) {
            return "circuit breaker open";
        }
        if (error instanceof WebClientRequestException) {
            return "connection failed: " + error.getMessage();
        }
        return "invalid answer: " + error.getMessage();
    }
}
