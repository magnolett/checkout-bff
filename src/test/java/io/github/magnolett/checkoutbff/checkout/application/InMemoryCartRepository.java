package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent;
import io.github.magnolett.checkoutbff.checkout.port.CartRepository;
import io.github.magnolett.checkoutbff.checkout.port.ConcurrentCartUpdateException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Honest in-memory repository: it stores snapshots (so only what was persisted is visible to the next read),
 * enforces the optimistic lock by version and keeps every persisted state for assertions.
 */
class InMemoryCartRepository implements CartRepository {

    private final Map<UUID, Cart> store = new HashMap<>();
    private final Map<Integer, RuntimeException> updateFailures = new HashMap<>();
    private final Map<Integer, RuntimeException> failuresAfterStoring = new HashMap<>();
    private final List<Cart> updates = new ArrayList<>();
    private final List<CheckoutEvent> persistedEvents = new ArrayList<>();
    private int updateCalls;

    void seed(Cart cart) {
        store.put(cart.id(), copy(cart));
    }

    /** Makes the nth call to update (1-based) fail with the given error, before anything is stored. */
    void failUpdate(int nthCall, RuntimeException error) {
        updateFailures.put(nthCall, error);
    }

    /** Makes the nth call to update store the cart and fail anyway, like a write whose acknowledgement is lost. */
    void failUpdateAfterStoring(int nthCall, RuntimeException error) {
        failuresAfterStoring.put(nthCall, error);
    }

    int updateCalls() {
        return updateCalls;
    }

    /** Snapshot of the cart as stored by each successful update, in order. */
    List<Cart> updates() {
        return updates;
    }

    List<CheckoutEvent> persistedEvents() {
        return persistedEvents;
    }

    Cart stored(UUID id) {
        return copy(store.get(id));
    }

    @Override
    public Mono<Cart> findById(UUID id) {
        return Mono.defer(() -> Mono.justOrEmpty(store.get(id)).map(InMemoryCartRepository::copy));
    }

    @Override
    public Mono<Cart> insert(Cart cart) {
        return Mono.fromSupplier(() -> {
            seed(cart);
            return cart;
        });
    }

    @Override
    public Mono<Cart> update(Cart cart) {
        return Mono.defer(() -> {
            updateCalls++;
            RuntimeException failure = updateFailures.get(updateCalls);
            if (failure != null) {
                return Mono.error(failure);
            }
            Cart current = store.get(cart.id());
            if (current == null || current.version() != cart.version()) {
                return Mono.error(new ConcurrentCartUpdateException(cart.id()));
            }
            persistedEvents.addAll(cart.newEvents());
            long newVersion = cart.version() + 1;
            cart.markPersisted(newVersion);
            Cart snapshot = copy(cart);
            store.put(cart.id(), snapshot);
            updates.add(copy(cart));
            RuntimeException lostAcknowledgement = failuresAfterStoring.get(updateCalls);
            return lostAcknowledgement == null ? Mono.just(cart) : Mono.error(lostAcknowledgement);
        });
    }

    private static Cart copy(Cart c) {
        return Cart.restore(c.id(), c.items(), c.paymentMethods(), c.total(), c.createdAt(), c.expiresAt(),
                c.status(), c.processingLeaseUntil(), c.attempts(), c.paidAt(), c.version());
    }
}
