package io.github.magnolett.checkoutbff.checkout;

import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentOrder;
import io.github.magnolett.checkoutbff.checkout.domain.Product;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared test data, in the shape of the challenge statement. */
public final class CheckoutFixtures {

    public static final Instant NOW = Instant.parse("2026-10-14T16:00:00Z");
    public static final Duration TTL = Duration.ofMinutes(30);
    public static final Duration LEASE = Duration.ofSeconds(30);

    public static final String CREDIT = "Cartão de Crédito";
    public static final String DEBIT = "Cartão de Débito";
    public static final String PIX = "Pix";

    public static final String CARD_NUMBER = "4111111111114242";
    public static final String CVV = "123";

    private CheckoutFixtures() {
    }

    public static Product premium() {
        return new Product("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
                Money.of("39.90", "BRL"));
    }

    public static Product basic() {
        return new Product("basico", "Plano Básico", "Assinatura mensal básica", Money.of("19.90", "BRL"));
    }

    public static List<PaymentMethod> allMethods() {
        return List.of(
                new PaymentMethod(PaymentMethodType.CREDIT_CARD, CREDIT),
                new PaymentMethod(PaymentMethodType.DEBIT_CARD, DEBIT),
                new PaymentMethod(PaymentMethodType.PIX, PIX));
    }

    public static Cart openCart() {
        return Cart.create(UUID.randomUUID(), List.of(premium()), allMethods(), NOW, TTL);
    }

    public static CardData card() {
        return new CardData("Ana Souza", CARD_NUMBER, "12/28", CVV);
    }

    public static CardData cardEndingIn(String lastFour) {
        return new CardData("Ana Souza", "411111111111" + lastFour, "12/28", CVV);
    }

    public static PaymentOrder creditOrder() {
        return new PaymentOrder(CREDIT, card(), null);
    }

    public static PaymentOrder creditOrder(String idempotencyKey) {
        return new PaymentOrder(CREDIT, card(), idempotencyKey);
    }

    public static PaymentOrder pixOrder() {
        return new PaymentOrder(PIX, null, null);
    }
}
