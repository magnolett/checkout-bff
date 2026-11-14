package io.github.magnolett.checkoutbff.integration.http;

import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Client of the products service. It owns the translation from that service's model (title, summary, pricing) to
 * the domain {@link Product}, so the rest of the BFF never sees the external format.
 *
 * <p>Resilience, from the inside out: a deadline per call, a circuit breaker that stops calling a sick service, and a
 * retry with backoff outside it, so every retry also goes through the breaker. A 404 is an answer, not a failure: the
 * product does not exist.
 */
public class ProductsServiceClient implements ProductCatalog {

    public static final String SERVICE = "products-service";

    private final WebClient webClient;
    private final Duration timeout;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ProductsServiceClient(WebClient webClient, Duration timeout, CircuitBreaker circuitBreaker, Retry retry) {
        this.webClient = webClient;
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Mono<Product> findBySlug(String slug) {
        return webClient.get()
                .uri("/products/{slug}", slug)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(ProductPayload.class);
                    }
                    if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return response.releaseBody().then(Mono.<ProductPayload>empty());
                    }
                    return response.<ProductPayload>createError();
                })
                .timeout(timeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .map(payload -> payload.toProduct(slug))
                .onErrorMap(error -> new ServiceUnavailableException(SERVICE, Failures.describe(error), error));
    }
}
