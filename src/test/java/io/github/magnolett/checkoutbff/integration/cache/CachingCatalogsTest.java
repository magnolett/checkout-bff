package io.github.magnolett.checkoutbff.integration.cache;

import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingCatalogsTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Product PREMIUM = new Product("premium", "Plano Premium", null, Money.of("39.90", "BRL"));
    private static final PaymentMethod PIX = new PaymentMethod(PaymentMethodType.PIX, "Pix");

    private final RedisJsonCache cache = mock(RedisJsonCache.class);
    private final ProductCatalog products = mock(ProductCatalog.class);
    private final PaymentMethodCatalog methods = mock(PaymentMethodCatalog.class);

    @Test
    void productHitSkipsTheService() {
        when(cache.get("checkout-bff:v1:product:premium", Product.class)).thenReturn(Mono.just(PREMIUM));

        StepVerifier.create(new CachingProductCatalog(products, cache, TTL).findBySlug("premium"))
                .expectNext(PREMIUM).verifyComplete();

        verify(products, never()).findBySlug(anyString());
    }

    @Test
    void productMissGoesToTheServiceAndFillsTheCache() {
        when(cache.get(anyString(), eq(Product.class))).thenReturn(Mono.empty());
        when(cache.put(anyString(), any(), any())).thenReturn(Mono.empty());
        when(products.findBySlug("premium")).thenReturn(Mono.just(PREMIUM));

        StepVerifier.create(new CachingProductCatalog(products, cache, TTL).findBySlug("premium"))
                .expectNext(PREMIUM).verifyComplete();

        verify(cache).put("checkout-bff:v1:product:premium", PREMIUM, TTL);
    }

    @Test
    void unknownProductIsNotCached() {
        when(cache.get(anyString(), eq(Product.class))).thenReturn(Mono.empty());
        when(products.findBySlug("nope")).thenReturn(Mono.empty());

        StepVerifier.create(new CachingProductCatalog(products, cache, TTL).findBySlug("nope")).verifyComplete();

        verify(cache, never()).put(anyString(), any(), any());
    }

    @Test
    void paymentMethodsMissFillsTheCache() {
        when(cache.get(anyString(), eq(PaymentMethod[].class))).thenReturn(Mono.empty());
        when(cache.put(anyString(), any(), any())).thenReturn(Mono.empty());
        when(methods.findAvailable()).thenReturn(Flux.just(PIX));

        StepVerifier.create(new CachingPaymentMethodCatalog(methods, cache, TTL).findAvailable())
                .expectNext(PIX).verifyComplete();

        verify(cache).put("checkout-bff:v1:payment-methods", List.of(PIX), TTL);
    }

    @Test
    void paymentMethodsHitSkipsTheService() {
        when(cache.get(anyString(), eq(PaymentMethod[].class))).thenReturn(Mono.just(new PaymentMethod[]{PIX}));

        StepVerifier.create(new CachingPaymentMethodCatalog(methods, cache, TTL).findAvailable())
                .expectNext(PIX).verifyComplete();

        verify(methods, never()).findAvailable();
    }

    @Test
    void emptyPaymentMethodsAreNeverCached() {
        when(cache.get(anyString(), eq(PaymentMethod[].class))).thenReturn(Mono.empty());
        when(methods.findAvailable()).thenReturn(Flux.empty());

        StepVerifier.create(new CachingPaymentMethodCatalog(methods, cache, TTL).findAvailable()).verifyComplete();

        verify(cache, never()).put(anyString(), any(), any());
    }
}
