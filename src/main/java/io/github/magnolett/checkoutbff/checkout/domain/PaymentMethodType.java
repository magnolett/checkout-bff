package io.github.magnolett.checkoutbff.checkout.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Stable identity of a payment method. The contract with the frontend identifies a method by its display name,
 * so the BFF resolves that name to one of these types using the data of the payment methods service.
 */
public enum PaymentMethodType {

    CREDIT_CARD(true),
    DEBIT_CARD(true),
    PIX(false);

    private final boolean requiresCard;

    PaymentMethodType(boolean requiresCard) {
        this.requiresCard = requiresCard;
    }

    public boolean requiresCard() {
        return requiresCard;
    }

    /** Types this BFF knows how to charge. Anything else coming from the service is not offered. */
    public static Optional<PaymentMethodType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(code.strip()))
                .findFirst();
    }
}
