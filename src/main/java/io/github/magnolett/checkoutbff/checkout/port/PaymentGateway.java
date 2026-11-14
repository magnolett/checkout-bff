package io.github.magnolett.checkoutbff.checkout.port;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import reactor.core.publisher.Mono;

/**
 * Charges a payment. Implementations must treat {@link PendingPayment#attemptId()} as the idempotency key, as real
 * gateways do, so a retried attempt never produces a second charge.
 */
public interface PaymentGateway {

    Mono<PaymentAuthorization> authorize(PendingPayment payment);
}
