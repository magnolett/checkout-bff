package io.github.magnolett.checkoutbff.integration.http;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * What deserves a retry and counts against the circuit breaker: timeout, connection failure, 5xx and 429. A 4xx is
 * a mistake in the request and would fail the same way again, so it is neither retried nor taken as a sign that the
 * dependency is sick. Referenced by class name in application.yml (resilience4j).
 */
public class TransientFailure implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable error) {
        return error instanceof TimeoutException
                || error instanceof WebClientRequestException
                || error instanceof WebClientResponseException response
                        && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429);
    }
}
