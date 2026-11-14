package io.github.magnolett.checkoutbff.integration.cache;

import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Cache-aside in front of the products service. Only hits are cached: a 404 is not, so a product published a minute
 * ago is not hidden by an old miss. The price shown here is not the price charged by accident: the cart keeps the
 * snapshot taken in the pre-checkout, and that is what the checkout charges.
 */
public class CachingProductCatalog implements ProductCatalog {

    static final String KEY_PREFIX = "checkout-bff:v1:product:";

    private final ProductCatalog source;
    private final RedisJsonCache cache;
    private final Duration ttl;

    public CachingProductCatalog(ProductCatalog source, RedisJsonCache cache, Duration ttl) {
        this.source = source;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public Mono<Product> findBySlug(String slug) {
        String key = KEY_PREFIX + slug;
        return cache.get(key, Product.class)
                .switchIfEmpty(Mono.defer(() -> source.findBySlug(slug)
                        .flatMap(product -> cache.put(key, product, ttl).thenReturn(product))));
    }
}
