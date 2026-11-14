package io.github.magnolett.checkoutbff.checkout.port;

import io.github.magnolett.checkoutbff.checkout.domain.Product;
import reactor.core.publisher.Mono;

/** Source of products (the products service). The BFF is not the owner of the catalog. */
public interface ProductCatalog {

    /**
     * Product with the given slug, or an empty Mono when the catalog does not know it.
     * Fails with {@link ServiceUnavailableException} only when the catalog cannot be reached.
     */
    Mono<Product> findBySlug(String slug);
}
