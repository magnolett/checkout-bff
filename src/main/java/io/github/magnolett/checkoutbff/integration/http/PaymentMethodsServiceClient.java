package io.github.magnolett.checkoutbff.integration.http;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Client of the payment methods service, with the same resilience as the products client. The list shown on the
 * payment screen comes from here, never from a list fixed in the BFF.
 */
@Slf4j
public class PaymentMethodsServiceClient implements PaymentMethodCatalog {

    public static final String SERVICE = "payment-methods-service";

    private final WebClient webClient;
    private final Duration timeout;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public PaymentMethodsServiceClient(WebClient webClient, Duration timeout, CircuitBreaker circuitBreaker,
                                       Retry retry) {
        this.webClient = webClient;
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Flux<PaymentMethod> findAvailable() {
        return webClient.get()
                .uri("/payment-methods")
                .retrieve()
                .bodyToMono(PaymentMethodsPayload.class)
                .timeout(timeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .map(PaymentMethodsServiceClient::toPaymentMethods)
                .onErrorMap(error -> new ServiceUnavailableException(SERVICE, Failures.describe(error), error))
                .flatMapIterable(methods -> methods);
    }

    /**
     * Keeps the enabled methods the BFF knows how to charge. An unknown code (a method the service started offering
     * before the BFF supports it) is left out instead of breaking the checkout.
     */
    static List<PaymentMethod> toPaymentMethods(PaymentMethodsPayload payload) {
        if (payload.paymentMethods() == null) {
            throw new IllegalArgumentException("payment methods list missing");
        }
        List<PaymentMethod> methods = new ArrayList<>();
        for (PaymentMethodsPayload.Item item : payload.paymentMethods()) {
            if (!Boolean.TRUE.equals(item.enabled()) || item.code() == null || item.displayName() == null
                    || item.displayName().isBlank()) {
                continue;
            }
            PaymentMethodType.fromCode(item.code()).ifPresentOrElse(
                    type -> methods.add(new PaymentMethod(type, item.displayName().strip())),
                    () -> log.info("payment method left out, unknown code={}", item.code()));
        }
        return methods;
    }
}
