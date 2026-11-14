package io.github.magnolett.checkoutbff.checkout.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One try to pay a cart. It keeps only what the success screen and the audit need: the last digits of the card,
 * never the full number or the CVV.
 *
 * <p>{@code idempotencyKeys} lists every Idempotency-Key that reached this attempt: the one that opened it and any new
 * one that resumed it later. Repeating any of those requests returns the outcome of this attempt.
 */
public record PaymentAttempt(
        UUID id,
        List<String> idempotencyKeys,
        String fingerprint,
        PaymentMethodType methodType,
        String methodName,
        String lastDigits,
        Money amount,
        AttemptStatus status,
        String transactionId,
        String declineReason,
        Instant startedAt,
        Instant completedAt) {

    public PaymentAttempt {
        idempotencyKeys = List.copyOf(idempotencyKeys);
    }

    static PaymentAttempt start(UUID id, PaymentOrder order, PaymentMethod method, Money amount, Instant now) {
        String lastDigits = order.card() == null ? null : order.card().lastDigits();
        List<String> keys = order.idempotencyKey() == null ? List.of() : List.of(order.idempotencyKey());
        return new PaymentAttempt(id, keys, order.fingerprint(), method.type(), method.name(), lastDigits, amount,
                AttemptStatus.PENDING, null, null, now, null);
    }

    /** True when a request carrying this key reached the attempt. */
    boolean answersTo(String idempotencyKey) {
        return idempotencyKeys.contains(idempotencyKey);
    }

    /** Resumed by a request with a key of its own: repeating that request has to find this attempt as well. */
    PaymentAttempt resumedWith(String idempotencyKey) {
        if (idempotencyKey == null || answersTo(idempotencyKey)) {
            return this;
        }
        List<String> keys = new ArrayList<>(idempotencyKeys);
        keys.add(idempotencyKey);
        return new PaymentAttempt(id, keys, fingerprint, methodType, methodName, lastDigits, amount, status,
                transactionId, declineReason, startedAt, completedAt);
    }

    PaymentAttempt approved(String transactionId, Instant now) {
        return new PaymentAttempt(id, idempotencyKeys, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.APPROVED, transactionId, null, startedAt, now);
    }

    PaymentAttempt declined(String reason, Instant now) {
        return new PaymentAttempt(id, idempotencyKeys, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.DECLINED, null, reason, startedAt, now);
    }

    PaymentAttempt abandoned(Instant now) {
        return new PaymentAttempt(id, idempotencyKeys, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.ABANDONED, null, null, startedAt, now);
    }
}
