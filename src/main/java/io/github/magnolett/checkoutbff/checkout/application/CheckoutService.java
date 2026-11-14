package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAttempt;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentOrder;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.checkout.port.CartRepository;
import io.github.magnolett.checkoutbff.checkout.port.ConcurrentCartUpdateException;
import io.github.magnolett.checkoutbff.checkout.port.PaymentGateway;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Checkout: charges a cart created in the pre-checkout.
 *
 * <ol>
 *   <li>the cart must exist;</li>
 *   <li>a request repeating an Idempotency-Key gets the outcome already recorded, with no new charge;</li>
 *   <li>a different instrument never replaces an attempt whose answer was lost before the provider says what became
 *       of it;</li>
 *   <li>the cart rules decide whether an attempt may start or resume (paid, in progress, expired, method, card);</li>
 *   <li>the move to PROCESSING is persisted with optimistic locking: of two simultaneous requests only one wins,
 *       the other gets PAYMENT_IN_PROGRESS, and the gateway is called once;</li>
 *   <li>the gateway has a deadline shorter than the processing lease;</li>
 *   <li>the outcome and its event are persisted in one atomic write (outbox).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    static final String PAYMENT_GATEWAY = "payment-gateway";

    private final CartRepository cartRepository;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final CheckoutProperties properties;

    public Mono<PaymentReceipt> pay(PayCommand command) {
        return cartRepository.findById(command.cartId())
                .switchIfEmpty(Mono.error(() -> new CheckoutException(CheckoutError.CART_NOT_FOUND, command.cartId())))
                .flatMap(cart -> replay(cart, command)
                        .switchIfEmpty(Mono.defer(() -> charge(cart, command))));
    }

    /**
     * Same key, same cart: the frontend is retrying a request whose answer it did not receive. A finished attempt
     * returns its outcome with no new charge. A pending one is either still running (409) or, once its lease is over,
     * resumed by {@link #charge}. A different payment under the same key is a client bug and is refused.
     */
    private Mono<PaymentReceipt> replay(Cart cart, PayCommand command) {
        return Mono.justOrEmpty(cart.attemptByIdempotencyKey(command.idempotencyKey()))
                .flatMap(attempt -> {
                    if (!attempt.fingerprint().equals(command.order().fingerprint())) {
                        return failure(CheckoutError.IDEMPOTENCY_KEY_REUSED, cart, "same key, different payment");
                    }
                    return switch (attempt.status()) {
                        case APPROVED -> {
                            log.info("idempotent replay cartId={} attemptId={}", cart.id(), attempt.id());
                            yield Mono.just(PaymentReceipt.from(cart, attempt));
                        }
                        case DECLINED -> failure(CheckoutError.PAYMENT_DECLINED, cart, "replay of a declined attempt");
                        case PENDING -> cart.isProcessing(now())
                                ? failure(CheckoutError.PAYMENT_IN_PROGRESS, cart, "attempt still running")
                                : Mono.<PaymentReceipt>empty();
                        case ABANDONED -> Mono.<PaymentReceipt>empty();
                    };
                });
    }

    private Mono<PaymentReceipt> charge(Cart cart, PayCommand command) {
        return settleLostAnswer(cart, command.order())
                .then(Mono.fromCallable(() -> cart.startPayment(command.order(), now(), properties.processingLease())))
                .flatMap(pending -> cartRepository.update(cart)
                        .onErrorMap(ConcurrentCartUpdateException.class, e -> inProgress(cart))
                        .then(Mono.defer(() -> authorize(cart, pending)))
                        .flatMap(authorization -> complete(cart, pending, authorization)));
    }

    /**
     * A different instrument must not leave behind an attempt whose answer was lost, because the provider may have
     * charged it. The provider is asked first, and its answer is saved before anything else. Approved: the cart is now
     * paid by that attempt, and this request ends as CART_ALREADY_PAID without touching the new instrument. Declined,
     * or never received by the provider: the attempt is closed and the new instrument goes ahead. No answer: 503, and
     * the attempt stays pending.
     */
    private Mono<Void> settleLostAnswer(Cart cart, PaymentOrder order) {
        return Mono.justOrEmpty(cart.attemptToSettleBefore(order, now()))
                .flatMap(unsettled -> paymentGateway.outcomeOf(unsettled.id())
                        .timeout(properties.paymentTimeout())
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .onErrorMap(error -> !(error instanceof ServiceUnavailableException),
                                error -> unknownOutcome("status query", error))
                        .flatMap(outcome -> {
                            cart.settle(unsettled.id(), outcome, now());
                            return cartRepository.update(cart)
                                    .onErrorMap(ConcurrentCartUpdateException.class, e -> inProgress(cart))
                                    .doOnNext(saved -> log.info("lost answer settled cartId={} attemptId={} outcome={}",
                                            cart.id(), unsettled.id(), describe(outcome)));
                        }))
                .then();
    }

    /**
     * Calls the gateway with a deadline shorter than the processing lease. Any failure here, a timeout or anything
     * else, leaves the outcome unknown: the attempt stays pending, the cart is released, and the customer gets a 503
     * telling them to try again. The retry is safe because it resumes the same attempt id, which is the provider's
     * idempotency key.
     */
    private Mono<PaymentAuthorization> authorize(Cart cart, PendingPayment pending) {
        return paymentGateway.authorize(pending)
                .timeout(properties.paymentTimeout())
                .onErrorMap(error -> !(error instanceof ServiceUnavailableException),
                        error -> unknownOutcome("authorization", error))
                .onErrorResume(error -> releaseLease(cart, pending.attemptId(), "gateway call failed")
                        .then(Mono.<PaymentAuthorization>error(error)));
    }

    private ServiceUnavailableException unknownOutcome(String call, Throwable error) {
        String detail = error instanceof TimeoutException
                ? call + " got no answer within " + properties.paymentTimeout()
                : call + " failed: " + error.getClass().getSimpleName();
        return new ServiceUnavailableException(PAYMENT_GATEWAY, detail, error);
    }

    private Mono<Void> releaseLease(Cart cart, UUID attemptId, String reason) {
        return Mono.fromRunnable(() -> cart.releaseLease(attemptId, now()))
                .then(Mono.defer(() -> cartRepository.update(cart)))
                .doOnNext(saved -> log.warn("{}, attempt kept pending cartId={} attemptId={}", reason, cart.id(),
                        attemptId))
                .onErrorResume(error -> {
                    log.warn("could not release the lease, it ends by itself cartId={} cause={}", cart.id(),
                            error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<PaymentReceipt> complete(Cart cart, PendingPayment pending, PaymentAuthorization authorization) {
        Instant now = now();
        Mono<PaymentReceipt> saved = switch (authorization) {
            case PaymentAuthorization.Approved approved -> {
                cart.approve(pending.attemptId(), approved.transactionId(), now);
                yield cartRepository.update(cart)
                        .map(CheckoutService::receiptOf)
                        .doOnNext(receipt -> log.info("payment approved cartId={} method={} attemptId={}",
                                cart.id(), pending.methodType(), pending.attemptId()));
            }
            case PaymentAuthorization.Declined declined -> {
                cart.decline(pending.attemptId(), declined.reason(), now);
                yield cartRepository.update(cart)
                        .doOnNext(stored -> log.info("payment declined cartId={} method={} attemptId={} reason={}",
                                cart.id(), pending.methodType(), pending.attemptId(), declined.reason()))
                        .then(failure(CheckoutError.PAYMENT_DECLINED, cart, declined.reason()));
            }
        };
        return saved.onErrorResume(error -> !(error instanceof CheckoutException),
                error -> outcomeNotSaved(cart.id(), pending.attemptId(), error));
    }

    /**
     * The provider answered, but saving the answer failed. The stored cart decides what the customer is told: the
     * write may have landed with its acknowledgement lost, or another request may have resumed the attempt after this
     * lease ran out and finished it, getting the same answer for the same attempt id. If the attempt is still pending,
     * a lost race means another request holds it now. A storage failure releases the lease instead, so the retry the
     * customer is told to make resumes the attempt at once.
     */
    private Mono<PaymentReceipt> outcomeNotSaved(UUID cartId, UUID attemptId, Throwable error) {
        return cartRepository.findById(cartId)
                .flatMap(stored -> stored.attempt(attemptId)
                        .map(attempt -> switch (attempt.status()) {
                            case APPROVED -> {
                                log.info("payment approved, outcome found already stored cartId={} attemptId={}",
                                        cartId, attemptId);
                                yield Mono.just(PaymentReceipt.from(stored, attempt));
                            }
                            case DECLINED -> failure(CheckoutError.PAYMENT_DECLINED, stored, attempt.declineReason());
                            case PENDING -> error instanceof ConcurrentCartUpdateException
                                    ? failure(CheckoutError.PAYMENT_IN_PROGRESS, stored,
                                            "another request holds this attempt")
                                    : releaseLease(stored, attemptId, "outcome not saved")
                                            .then(Mono.<PaymentReceipt>error(error));
                            case ABANDONED -> Mono.<PaymentReceipt>error(error);
                        })
                        .orElseGet(() -> Mono.error(error)))
                .switchIfEmpty(Mono.error(error))
                .onErrorResume(lookupFailure -> lookupFailure != error && !(lookupFailure instanceof CheckoutException),
                        lookupFailure -> Mono.error(error));
    }

    private static PaymentReceipt receiptOf(Cart cart) {
        PaymentAttempt approved = cart.approvedAttempt()
                .orElseThrow(() -> new IllegalStateException("paid cart without approved attempt " + cart.id()));
        return PaymentReceipt.from(cart, approved);
    }

    private static String describe(Optional<PaymentAuthorization> outcome) {
        return outcome.map(authorization -> authorization.getClass().getSimpleName()).orElse("never received");
    }

    private static CheckoutException inProgress(Cart cart) {
        return new CheckoutException(CheckoutError.PAYMENT_IN_PROGRESS, cart.id(), "another request is paying this cart");
    }

    private static Mono<PaymentReceipt> failure(CheckoutError error, Cart cart, String detail) {
        return Mono.error(new CheckoutException(error, cart.id(), detail));
    }

    /** Seconds precision: matches the contract ("2026-10-14T16:00:00Z") and survives a round trip to storage. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
