package io.github.magnolett.checkoutbff.checkout.domain;

/** Gateway answer for a payment. Sealed, so every caller must handle both outcomes. */
public sealed interface PaymentAuthorization {

    /** Approved charge. For Pix the transaction id is the end-to-end id; for cards, the authorization code. */
    record Approved(String transactionId) implements PaymentAuthorization {
    }

    record Declined(String reason) implements PaymentAuthorization {
    }
}
