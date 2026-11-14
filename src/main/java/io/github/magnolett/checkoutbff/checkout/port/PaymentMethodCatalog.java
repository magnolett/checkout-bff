package io.github.magnolett.checkoutbff.checkout.port;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import reactor.core.publisher.Flux;

/** Source of payment methods (the payment methods service). Never a fixed list in the BFF. */
public interface PaymentMethodCatalog {

    /**
     * Payment methods currently enabled, restricted to the types this BFF knows how to charge.
     * Fails with {@link ServiceUnavailableException} when the service cannot be reached.
     */
    Flux<PaymentMethod> findAvailable();
}
