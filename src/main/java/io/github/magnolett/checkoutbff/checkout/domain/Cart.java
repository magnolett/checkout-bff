package io.github.magnolett.checkoutbff.checkout.domain;

import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentApproved;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentDeclined;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cart aggregate: the snapshot built in the pre-checkout (products, prices and offered payment methods) plus the
 * payment attempts. Every checkout rule lives here, with no framework and no I/O.
 *
 * <pre>
 *   OPEN ──startPayment──▶ PROCESSING ──approve──▶ PAID (terminal)
 *                              │
 *                              └──decline──▶ DECLINED ──startPayment──▶ PROCESSING ...
 * </pre>
 *
 * Concurrency is handled outside, by optimistic locking on {@link #version()}: two requests that read the same
 * version cannot both persist the move to PROCESSING. The processing lease keeps a crashed instance from locking
 * the cart forever.
 *
 * <p>When the gateway answer is lost, the attempt stays pending with its outcome unknown. The same instrument resumes
 * it. A different one is accepted only after the provider says what became of it ({@link #settle}), so a charge that
 * went through is never followed by a second one.
 */
public final class Cart {

    private final UUID id;
    private final List<Product> items;
    private final List<PaymentMethod> paymentMethods;
    private final Money total;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final List<PaymentAttempt> attempts;
    private final List<CheckoutEvent> newEvents = new ArrayList<>();
    private CartStatus status;
    private Instant processingLeaseUntil;
    private Instant paidAt;
    private long version;

    private Cart(UUID id, List<Product> items, List<PaymentMethod> paymentMethods, Money total, Instant createdAt,
                 Instant expiresAt, CartStatus status, Instant processingLeaseUntil, List<PaymentAttempt> attempts,
                 Instant paidAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.items = List.copyOf(items);
        this.paymentMethods = List.copyOf(paymentMethods);
        this.total = Objects.requireNonNull(total, "total");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = Objects.requireNonNull(status, "status");
        this.processingLeaseUntil = processingLeaseUntil;
        this.attempts = new ArrayList<>(attempts);
        this.paidAt = paidAt;
        this.version = version;
    }

    /**
     * New cart from the pre-checkout. The total is the sum of the snapshot prices, so every product must be priced in
     * the same currency: plans in different currencies cannot share an order.
     */
    public static Cart create(UUID id, List<Product> items, List<PaymentMethod> paymentMethods, Instant now,
                              Duration ttl) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a cart needs at least one product");
        }
        if (paymentMethods.isEmpty()) {
            throw new IllegalArgumentException("a cart needs at least one payment method");
        }
        String currency = items.getFirst().price().currency();
        if (items.stream().anyMatch(item -> !item.price().currency().equals(currency))) {
            throw new CheckoutException(CheckoutError.PRODUCTS_NOT_COMBINABLE, null,
                    "products priced in different currencies");
        }
        Money total = items.stream().map(Product::price).reduce(Money::plus).orElseThrow();
        return new Cart(id, items, paymentMethods, total, now, now.plus(ttl), CartStatus.OPEN, null, List.of(), null,
                0L);
    }

    /** Rebuilds a cart read from storage. */
    public static Cart restore(UUID id, List<Product> items, List<PaymentMethod> paymentMethods, Money total,
                               Instant createdAt, Instant expiresAt, CartStatus status, Instant processingLeaseUntil,
                               List<PaymentAttempt> attempts, Instant paidAt, long version) {
        return new Cart(id, items, paymentMethods, total, createdAt, expiresAt, status, processingLeaseUntil,
                attempts, paidAt, version);
    }

    /**
     * Opens a payment attempt, or resumes the pending one.
     *
     * <p>Resuming covers the lost answer: the gateway call timed out, or its outcome could not be saved, and nobody
     * knows whether the customer was charged. If the same instrument comes back (same method, last digits and
     * expiry), the attempt keeps its id. That id is the idempotency key sent to the gateway, so a charge that went
     * through is returned instead of repeated. A different instrument needs the pending attempt settled first
     * ({@link #attemptToSettleBefore}).
     *
     * <p>The other checks follow the order in which the customer can fix them: a paid or expired cart cannot be fixed
     * on the payment screen, a wrong method or card can. Expiry blocks a new charge, never the end of one that
     * started in time.
     */
    public PendingPayment startPayment(PaymentOrder order, Instant now, Duration processingLease) {
        if (status == CartStatus.PAID) {
            throw failure(CheckoutError.CART_ALREADY_PAID, null);
        }
        if (isProcessing(now)) {
            throw failure(CheckoutError.PAYMENT_IN_PROGRESS, null);
        }
        Optional<PaymentAttempt> resumable = pendingAttempt()
                .filter(pending -> pending.fingerprint().equals(order.fingerprint()));
        if (resumable.isEmpty() && !now.isBefore(expiresAt)) {
            throw failure(CheckoutError.CART_EXPIRED, null);
        }
        PaymentMethod method = offeredMethod(order.methodName())
                .orElseThrow(() -> failure(CheckoutError.PAYMENT_METHOD_NOT_ALLOWED, "method not offered in this cart"));
        validateInstrument(method.type(), order.card(), now);

        PaymentAttempt attempt = resumable
                .map(pending -> resume(pending, order.idempotencyKey()))
                .orElseGet(() -> openAttempt(order, method, now));
        status = CartStatus.PROCESSING;
        processingLeaseUntil = now.plus(processingLease);
        return new PendingPayment(id, attempt.id(), method.type(), total, order.card());
    }

    /**
     * The pending attempt that must be settled before this order can be charged, if any.
     *
     * <p>A pending attempt outside its lease is one whose answer was lost. Coming back with the same instrument
     * resumes it, and the gateway answers for the original charge. Coming back with a different one would leave that
     * charge behind, so the provider has to say first what became of it. Empty when the cart is paid or busy, which
     * {@link #startPayment} refuses with the proper error.
     */
    public Optional<PaymentAttempt> attemptToSettleBefore(PaymentOrder order, Instant now) {
        if (status == CartStatus.PAID || isProcessing(now)) {
            return Optional.empty();
        }
        return pendingAttempt().filter(pending -> !pending.fingerprint().equals(order.fingerprint()));
    }

    /**
     * Records what the provider says about a pending attempt whose answer was lost. Approved: the cart is paid by it,
     * exactly as if the answer had arrived in time, so no other instrument gets charged. Declined: recorded as
     * declined. Unknown to the provider: abandoned, because the request never reached it.
     */
    public void settle(UUID attemptId, Optional<PaymentAuthorization> outcome, Instant now) {
        if (isProcessing(now)) {
            throw new IllegalStateException("cart " + id + " is still processing attempt " + attemptId);
        }
        if (outcome.isEmpty()) {
            abandon(attemptId, now);
            return;
        }
        switch (outcome.get()) {
            case PaymentAuthorization.Approved approved -> approve(attemptId, approved.transactionId(), now);
            case PaymentAuthorization.Declined declined -> decline(attemptId, declined.reason(), now);
        }
    }

    public void approve(UUID attemptId, String transactionId, Instant now) {
        PaymentAttempt attempt = pendingAttempt(attemptId);
        replace(attempt, attempt.approved(transactionId, now));
        status = CartStatus.PAID;
        paidAt = now;
        processingLeaseUntil = null;
        newEvents.add(new PaymentApproved(UUID.randomUUID(), id, now, attempt.methodType(), total, productSlugs(),
                transactionId));
    }

    public void decline(UUID attemptId, String reason, Instant now) {
        PaymentAttempt attempt = pendingAttempt(attemptId);
        replace(attempt, attempt.declined(reason, now));
        status = CartStatus.DECLINED;
        processingLeaseUntil = null;
        newEvents.add(new PaymentDeclined(UUID.randomUUID(), id, now, attempt.methodType(), total, reason));
    }

    /**
     * The gateway gave no answer. The attempt stays pending, because the outcome is unknown, but the cart is released
     * at once: the retry resumes the same attempt id, and the gateway deduplicates it.
     */
    public void releaseLease(UUID attemptId, Instant now) {
        pendingAttempt(attemptId);
        processingLeaseUntil = now;
    }

    /** True while an attempt holds the cart: another request must wait instead of calling the gateway again. */
    public boolean isProcessing(Instant now) {
        return status == CartStatus.PROCESSING && processingLeaseUntil != null && processingLeaseUntil.isAfter(now);
    }

    /** Latest attempt reached by a request with the given idempotency key. */
    public Optional<PaymentAttempt> attemptByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).answersTo(idempotencyKey)) {
                return Optional.of(attempts.get(i));
            }
        }
        return Optional.empty();
    }

    public Optional<PaymentAttempt> attempt(UUID attemptId) {
        return attempts.stream().filter(a -> a.id().equals(attemptId)).findFirst();
    }

    public Optional<PaymentAttempt> approvedAttempt() {
        return attempts.stream().filter(a -> a.status() == AttemptStatus.APPROVED).findFirst();
    }

    public List<String> productSlugs() {
        return items.stream().map(Product::slug).toList();
    }

    /** Events raised since the cart was read; the repository stores them atomically with the new state. */
    public List<CheckoutEvent> newEvents() {
        return List.copyOf(newEvents);
    }

    /** Called by the repository after a successful write. */
    public void markPersisted(long newVersion) {
        this.version = newVersion;
        this.newEvents.clear();
    }

    private PaymentAttempt resume(PaymentAttempt pending, String idempotencyKey) {
        PaymentAttempt resumed = pending.resumedWith(idempotencyKey);
        replace(pending, resumed);
        return resumed;
    }

    private PaymentAttempt openAttempt(PaymentOrder order, PaymentMethod method, Instant now) {
        pendingAttempt().ifPresent(unsettled -> {
            throw new IllegalStateException("attempt " + unsettled.id() + " of cart " + id
                    + " has an unknown outcome: settle it with the provider before opening another one");
        });
        PaymentAttempt attempt = PaymentAttempt.start(UUID.randomUUID(), order, method, total, now);
        attempts.add(attempt);
        return attempt;
    }

    private void abandon(UUID attemptId, Instant now) {
        PaymentAttempt attempt = pendingAttempt(attemptId);
        replace(attempt, attempt.abandoned(now));
        status = CartStatus.OPEN;
        processingLeaseUntil = null;
    }

    private Optional<PaymentMethod> offeredMethod(String methodName) {
        return paymentMethods.stream().filter(m -> m.name().equals(methodName)).findFirst();
    }

    private void validateInstrument(PaymentMethodType type, CardData card, Instant now) {
        if (type.requiresCard() && card == null) {
            throw failure(CheckoutError.INVALID_PAYMENT_DATA, "card data is required for " + type);
        }
        if (!type.requiresCard() && card != null) {
            throw failure(CheckoutError.INVALID_PAYMENT_DATA, "card data is not accepted for " + type);
        }
        if (card != null) {
            YearMonth currentMonth = YearMonth.from(now.atZone(ZoneOffset.UTC));
            card.firstInvalidField(currentMonth).ifPresent(field -> {
                throw failure(CheckoutError.INVALID_PAYMENT_DATA, "invalid card field: " + field);
            });
        }
    }

    /** At most one attempt is pending at a time: a new one opens only after the pending one is settled. */
    private Optional<PaymentAttempt> pendingAttempt() {
        return attempts.stream().filter(a -> a.status() == AttemptStatus.PENDING).findFirst();
    }

    private PaymentAttempt pendingAttempt(UUID attemptId) {
        return pendingAttempt()
                .filter(a -> a.id().equals(attemptId))
                .orElseThrow(() -> new IllegalStateException("no pending attempt " + attemptId + " in cart " + id));
    }

    private void replace(PaymentAttempt current, PaymentAttempt updated) {
        attempts.set(attempts.indexOf(current), updated);
    }

    private CheckoutException failure(CheckoutError error, String detail) {
        return new CheckoutException(error, id, detail);
    }

    public UUID id() {
        return id;
    }

    public List<Product> items() {
        return items;
    }

    public List<PaymentMethod> paymentMethods() {
        return paymentMethods;
    }

    public Money total() {
        return total;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public CartStatus status() {
        return status;
    }

    public Instant processingLeaseUntil() {
        return processingLeaseUntil;
    }

    public List<PaymentAttempt> attempts() {
        return List.copyOf(attempts);
    }

    public Instant paidAt() {
        return paidAt;
    }

    public long version() {
        return version;
    }
}
