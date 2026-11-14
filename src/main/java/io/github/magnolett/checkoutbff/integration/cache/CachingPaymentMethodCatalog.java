package io.github.magnolett.checkoutbff.integration.cache;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;

/** Cache-aside in front of the payment methods service. An empty list is never cached. */
public class CachingPaymentMethodCatalog implements PaymentMethodCatalog {

    static final String KEY = "checkout-bff:v1:payment-methods";

    private final PaymentMethodCatalog source;
    private final RedisJsonCache cache;
    private final Duration ttl;

    public CachingPaymentMethodCatalog(PaymentMethodCatalog source, RedisJsonCache cache, Duration ttl) {
        this.source = source;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public Flux<PaymentMethod> findAvailable() {
        return cache.get(KEY, PaymentMethod[].class)
                .map(Arrays::asList)
                .switchIfEmpty(Mono.defer(() -> source.findAvailable().collectList()
                        .flatMap(methods -> methods.isEmpty()
                                ? Mono.just(methods)
                                : cache.put(KEY, methods, ttl).thenReturn(methods))))
                .flatMapIterable(methods -> methods);
    }
}
