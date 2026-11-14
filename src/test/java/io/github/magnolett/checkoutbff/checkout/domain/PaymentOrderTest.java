package io.github.magnolett.checkoutbff.checkout.domain;

import org.junit.jupiter.api.Test;

import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.CREDIT;
import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.DEBIT;
import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.PIX;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentOrderTest {

    private static PaymentOrder credit(String number, String expiry, String cvv, String key) {
        return new PaymentOrder(CREDIT, new CardData("Ana Souza", number, expiry, cvv), key);
    }

    @Test
    void fingerprintNeverContainsTheFullNumberOrTheCvv() {
        String fingerprint = credit("4111111111114242", "12/28", "987", null).fingerprint();

        assertThat(fingerprint).doesNotContain("4111111111114242").doesNotContain("411111111111");
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    void sameMethodLastDigitsAndExpiryGiveTheSameFingerprint() {
        PaymentOrder first = credit("4111111111114242", "12/28", "123", "key-a");
        PaymentOrder second = credit("5555555555554242", "12/28", "999", "key-b");

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    void methodNameChangesTheFingerprint() {
        CardData card = new CardData("Ana Souza", "4111111111114242", "12/28", "123");

        assertThat(new PaymentOrder(CREDIT, card, null).fingerprint())
                .isNotEqualTo(new PaymentOrder(DEBIT, card, null).fingerprint());
    }

    @Test
    void lastDigitsChangeTheFingerprint() {
        assertThat(credit("4111111111114242", "12/28", "123", null).fingerprint())
                .isNotEqualTo(credit("4111111111114243", "12/28", "123", null).fingerprint());
    }

    @Test
    void expiryChangesTheFingerprint() {
        assertThat(credit("4111111111114242", "12/28", "123", null).fingerprint())
                .isNotEqualTo(credit("4111111111114242", "11/28", "123", null).fingerprint());
    }

    @Test
    void pixHasAStableFingerprintDifferentFromACardPayment() {
        PaymentOrder pix = new PaymentOrder(PIX, null, null);

        assertThat(pix.fingerprint()).isEqualTo(new PaymentOrder(PIX, null, "any-key").fingerprint());
        assertThat(pix.fingerprint()).isNotEqualTo(credit("4111111111114242", "12/28", "123", null).fingerprint());
    }
}
