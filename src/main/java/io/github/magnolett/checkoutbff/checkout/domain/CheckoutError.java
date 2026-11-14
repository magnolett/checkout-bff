package io.github.magnolett.checkoutbff.checkout.domain;

/**
 * Business failures of the checkout. The domain only names the reason; how each one is shown to the customer
 * (HTTP status, display type, texts and buttons) belongs to the BFF layer.
 */
public enum CheckoutError {
    PRODUCT_NOT_FOUND,
    CART_NOT_FOUND,
    CART_EXPIRED,
    CART_ALREADY_PAID,
    PAYMENT_METHOD_NOT_ALLOWED,
    INVALID_PAYMENT_DATA,
    PAYMENT_DECLINED,
    PAYMENT_IN_PROGRESS,
    IDEMPOTENCY_KEY_REUSED
}
