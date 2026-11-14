package io.github.magnolett.checkoutbff.checkout.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What the customer asked to pay with: the method name chosen on screen, the card (absent for Pix) and the optional
 * idempotency key sent by the frontend.
 */
public record PaymentOrder(String methodName, CardData card, String idempotencyKey) {

    /**
     * Fingerprint used to detect an idempotency key reused with a different payment. It never contains the full
     * card number or the CVV: only the method, the last digits and the expiry, hashed.
     */
    public String fingerprint() {
        String cardPart = card == null ? "-" : card.lastDigits() + "|" + card.expiry();
        return sha256(methodName + "|" + cardPart);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
