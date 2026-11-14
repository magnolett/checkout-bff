package io.github.magnolett.checkoutbff.checkout.port;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CartRepository {

    Mono<Cart> findById(UUID id);

    Mono<Cart> insert(Cart cart);

    /**
     * Persists the new state and the events raised since the cart was read, in one atomic write.
     * Fails with {@link ConcurrentCartUpdateException} when someone else changed the cart in the meantime.
     */
    Mono<Cart> update(Cart cart);
}
