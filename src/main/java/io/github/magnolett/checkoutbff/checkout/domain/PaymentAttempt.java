package io.github.magnolett.checkoutbff.checkout.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One try to pay a cart. It keeps only what the success screen and the audit need: the last digits of the card,
 * never the full number or the CVV.
 */
public record PaymentAttempt(
        UUID id,
        String idempotencyKey,
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

    static PaymentAttempt start(UUID id, PaymentOrder order, PaymentMethod method, Money amount, Instant now) {
        String lastDigits = order.card() == null ? null : order.card().lastDigits();
        return new PaymentAttempt(id, order.idempotencyKey(), order.fingerprint(), method.type(), method.name(),
                lastDigits, amount, AttemptStatus.PENDING, null, null, now, null);
    }

    PaymentAttempt approved(String transactionId, Instant now) {
        return new PaymentAttempt(id, idempotencyKey, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.APPROVED, transactionId, null, startedAt, now);
    }

    PaymentAttempt declined(String reason, Instant now) {
        return new PaymentAttempt(id, idempotencyKey, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.DECLINED, null, reason, startedAt, now);
    }

    PaymentAttempt abandoned(Instant now) {
        return new PaymentAttempt(id, idempotencyKey, fingerprint, methodType, methodName, lastDigits, amount,
                AttemptStatus.ABANDONED, null, null, startedAt, now);
    }
}
