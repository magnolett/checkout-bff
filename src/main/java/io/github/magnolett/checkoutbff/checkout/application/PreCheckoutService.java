package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.CartRepository;
import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Pre-checkout: builds the cart for the payment screen.
 *
 * <p>Products and payment methods come from two independent services, so both lookups are in flight at the same
 * time ({@code Mono.zip}): the screen waits for the slowest call, not for the sum of both. Products of a multi-item
 * request are also fetched concurrently, keeping the requested order ({@code flatMapSequential}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreCheckoutService {

    private final ProductCatalog productCatalog;
    private final PaymentMethodCatalog paymentMethodCatalog;
    private final CartRepository cartRepository;
    private final Clock clock;
    private final CheckoutProperties properties;

    public Mono<Cart> start(List<String> slugs) {
        Mono<List<Product>> products = Flux.fromIterable(slugs)
                .flatMapSequential(this::findProduct)
                .collectList();

        Mono<List<PaymentMethod>> paymentMethods = paymentMethodCatalog.findAvailable()
                .collectList()
                .filter(methods -> !methods.isEmpty())
                .switchIfEmpty(Mono.error(() -> new ServiceUnavailableException(
                        "payment-methods-service", "no payment method enabled")));

        return Mono.zip(products, paymentMethods)
                .map(found -> Cart.create(UUID.randomUUID(), found.getT1(), found.getT2(),
                        clock.instant().truncatedTo(ChronoUnit.SECONDS), properties.cartTtl()))
                .flatMap(cartRepository::insert)
                .doOnNext(cart -> log.info("cart created cartId={} products={} total={}",
                        cart.id(), cart.productSlugs(), cart.total().amount()));
    }

    private Mono<Product> findProduct(String slug) {
        return productCatalog.findBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new CheckoutException(
                        CheckoutError.PRODUCT_NOT_FOUND, null, "slug=" + slug)));
    }
}
