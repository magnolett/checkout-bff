package io.github.magnolett.checkoutbff.checkout.port;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Charges a payment. Implementations must treat {@link PendingPayment#attemptId()} as the idempotency key, as real
 * gateways do, so a retried attempt never produces a second charge.
 */
public interface PaymentGateway {

    Mono<PaymentAuthorization> authorize(PendingPayment payment);

    /**
     * What the provider did with an attempt whose answer was lost: the outcome it recorded, or empty when the request
     * never reached it. This is the status query every provider offers by idempotency key, asked before a different
     * instrument replaces that attempt.
     */
    Mono<PaymentAuthorization> outcomeOf(UUID attemptId);
}
