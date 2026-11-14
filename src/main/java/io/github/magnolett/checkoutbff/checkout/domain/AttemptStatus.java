package io.github.magnolett.checkoutbff.checkout.domain;

/** Outcome of a single payment attempt. ABANDONED marks an attempt whose processing lease expired without outcome. */
public enum AttemptStatus {
    PENDING,
    APPROVED,
    DECLINED,
    ABANDONED
}
