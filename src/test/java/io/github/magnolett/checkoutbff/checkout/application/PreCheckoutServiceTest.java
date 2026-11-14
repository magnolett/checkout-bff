package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CartStatus;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import io.github.magnolett.checkoutbff.checkout.port.CartRepository;
import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreCheckoutServiceTest {

    private ProductCatalog productCatalog;
    private PaymentMethodCatalog paymentMethodCatalog;
    private CartRepository cartRepository;
    private TestClock clock;
    private PreCheckoutService service;

    @BeforeEach
    void setUp() {
        productCatalog = mock(ProductCatalog.class);
        paymentMethodCatalog = mock(PaymentMethodCatalog.class);
        cartRepository = mock(CartRepository.class);
        clock = new TestClock(NOW);
        when(cartRepository.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        service = new PreCheckoutService(productCatalog, paymentMethodCatalog, cartRepository, clock,
                new CheckoutProperties(TTL, Duration.ofSeconds(10), LEASE));
    }

    private void catalogHas(Product... products) {
        for (Product product : products) {
            when(productCatalog.findBySlug(product.slug())).thenReturn(Mono.just(product));
        }
    }

    private void methodsAre(List<PaymentMethod> methods) {
        when(paymentMethodCatalog.findAvailable()).thenReturn(Flux.fromIterable(methods));
    }

    @Test
    void buildsAnOpenCartWithProductSnapshotAndOfferedMethods() {
        catalogHas(premium());
        methodsAre(allMethods());

        StepVerifier.create(service.start(List.of("premium")))
                .assertNext(cart -> {
                    assertThat(cart.id()).isNotNull();
                    assertThat(cart.status()).isEqualTo(CartStatus.OPEN);
                    assertThat(cart.items()).containsExactly(premium());
                    assertThat(cart.paymentMethods()).containsExactlyElementsOf(allMethods());
                    assertThat(cart.total()).isEqualTo(Money.of("39.90", "BRL"));
                    assertThat(cart.createdAt()).isEqualTo(NOW);
                    assertThat(cart.expiresAt()).isEqualTo(NOW.plus(TTL));
                })
                .verifyComplete();
    }

    @Test
    void persistsTheCartThatItReturns() {
        catalogHas(premium());
        methodsAre(allMethods());

        Cart returned = service.start(List.of("premium")).block();

        verify(cartRepository).insert(returned);
    }

    @Test
    void eachPreCheckoutCreatesANewCart() {
        catalogHas(premium());
        methodsAre(allMethods());

        Cart first = service.start(List.of("premium")).block();
        Cart second = service.start(List.of("premium")).block();

        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void totalIsTheSumOfAllProducts() {
        catalogHas(premium(), basic());
        methodsAre(allMethods());

        Cart cart = service.start(List.of("premium", "basico")).block();

        assertThat(cart.total()).isEqualTo(Money.of("59.80", "BRL"));
    }

    @Test
    void cartTimesHaveSecondsPrecision() {
        clock.set(Instant.parse("2026-10-14T16:00:00.789Z"));
        catalogHas(premium());
        methodsAre(allMethods());

        Cart cart = service.start(List.of("premium")).block();

        assertThat(cart.createdAt()).isEqualTo(NOW);
        assertThat(cart.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void productsAndPaymentMethodsAreFetchedInParallelSoTheWaitIsTheSlowestNotTheSum() {
        when(productCatalog.findBySlug("premium"))
                .thenAnswer(inv -> Mono.just(premium()).delayElement(Duration.ofSeconds(3)));
        when(paymentMethodCatalog.findAvailable())
                .thenAnswer(inv -> Flux.fromIterable(allMethods()).delaySubscription(Duration.ofSeconds(2)));

        StepVerifier.withVirtualTime(() -> service.start(List.of("premium")))
                .expectSubscription()
                // sequential calls would need 5s: nothing may arrive before the slowest (3s)
                .expectNoEvent(Duration.ofMillis(2900))
                .thenAwait(Duration.ofMillis(200))
                .assertNext(cart -> assertThat(cart.items()).containsExactly(premium()))
                .verifyComplete();
    }

    @Test
    void whenPaymentMethodsAreTheSlowestTheWaitIsTheirOwnLatency() {
        when(productCatalog.findBySlug("premium"))
                .thenAnswer(inv -> Mono.just(premium()).delayElement(Duration.ofSeconds(1)));
        when(paymentMethodCatalog.findAvailable())
                .thenAnswer(inv -> Flux.fromIterable(allMethods()).delaySubscription(Duration.ofSeconds(4)));

        StepVerifier.withVirtualTime(() -> service.start(List.of("premium")))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(3900))
                .thenAwait(Duration.ofMillis(200))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void productsOfAMultiItemRequestAreFetchedConcurrentlyAndKeepTheRequestedOrder() {
        // the first product is the slowest one: completion order is the reverse of the requested order
        when(productCatalog.findBySlug("premium"))
                .thenAnswer(inv -> Mono.just(premium()).delayElement(Duration.ofSeconds(3)));
        when(productCatalog.findBySlug("basico"))
                .thenAnswer(inv -> Mono.just(basic()).delayElement(Duration.ofSeconds(1)));
        methodsAre(allMethods());

        StepVerifier.withVirtualTime(() -> service.start(List.of("premium", "basico")))
                .expectSubscription()
                // sequential lookups would need 4s
                .expectNoEvent(Duration.ofMillis(2900))
                .thenAwait(Duration.ofMillis(200))
                .assertNext(cart -> {
                    assertThat(cart.productSlugs()).containsExactly("premium", "basico");
                    assertThat(cart.items()).extracting(Product::name).containsExactly("Plano Premium", "Plano Básico");
                })
                .verifyComplete();
    }

    @Test
    void unknownSlugIsProductNotFoundWithoutCartId() {
        when(productCatalog.findBySlug("ghost")).thenReturn(Mono.empty());
        methodsAre(allMethods());

        StepVerifier.create(service.start(List.of("ghost")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(CheckoutException.class, e -> {
                    assertThat(e.error()).isEqualTo(CheckoutError.PRODUCT_NOT_FOUND);
                    assertThat(e.cartId()).isNull();
                }))
                .verify();
        verify(cartRepository, never()).insert(any());
    }

    @Test
    void oneUnknownSlugAmongValidOnesFailsTheWholePreCheckout() {
        catalogHas(premium());
        when(productCatalog.findBySlug("ghost")).thenReturn(Mono.empty());
        methodsAre(allMethods());

        StepVerifier.create(service.start(List.of("premium", "ghost")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(CheckoutException.class,
                        e -> assertThat(e.error()).isEqualTo(CheckoutError.PRODUCT_NOT_FOUND)))
                .verify();
        verify(cartRepository, never()).insert(any());
    }

    @Test
    void emptyPaymentMethodsIsServiceUnavailable() {
        catalogHas(premium());
        when(paymentMethodCatalog.findAvailable()).thenReturn(Flux.empty());

        StepVerifier.create(service.start(List.of("premium")))
                .expectError(ServiceUnavailableException.class)
                .verify();
        verify(cartRepository, never()).insert(any());
    }

    @Test
    void anUnreachableProductsServiceIsServiceUnavailable() {
        when(productCatalog.findBySlug("premium"))
                .thenReturn(Mono.error(new ServiceUnavailableException("products-service", "timeout")));
        methodsAre(allMethods());

        StepVerifier.create(service.start(List.of("premium")))
                .expectError(ServiceUnavailableException.class)
                .verify();
        verify(cartRepository, never()).insert(any());
    }

    @Test
    void anUnreachablePaymentMethodsServiceIsServiceUnavailable() {
        catalogHas(premium());
        when(paymentMethodCatalog.findAvailable())
                .thenReturn(Flux.error(new ServiceUnavailableException("payment-methods-service", "down")));

        StepVerifier.create(service.start(List.of("premium")))
                .expectError(ServiceUnavailableException.class)
                .verify();
        verify(cartRepository, never()).insert(any());
    }

    @Test
    void aRepositoryFailureSurfacesToTheCaller() {
        catalogHas(premium());
        methodsAre(allMethods());
        org.mockito.Mockito.doReturn(Mono.error(new IllegalStateException("mongo down")))
                .when(cartRepository).insert(any());

        StepVerifier.create(service.start(List.of("premium")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void offersExactlyWhatTheServiceReturnedNotAFixedList() {
        List<PaymentMethod> onlyPix = List.of(new PaymentMethod(PaymentMethodType.PIX, "Pix"));
        catalogHas(premium());
        methodsAre(onlyPix);

        Cart cart = service.start(List.of("premium")).block();

        assertThat(cart.paymentMethods()).containsExactlyElementsOf(onlyPix);
    }
}
