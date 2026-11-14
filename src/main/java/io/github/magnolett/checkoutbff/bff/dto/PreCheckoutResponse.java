package io.github.magnolett.checkoutbff.bff.dto;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payment screen: products with description and the payment methods offered, by name only. */
public record PreCheckoutResponse(UUID cartId, List<ProductView> products, List<PaymentMethodView> paymentMethods) {

    public record ProductView(
            @Schema(example = "premium") String slug,
            @Schema(example = "Plano Premium") String name,
            @Schema(example = "Assinatura mensal com catálogo completo") String description,
            @Schema(example = "39.90") BigDecimal price,
            @Schema(example = "BRL") String currency) {
    }

    public record PaymentMethodView(@Schema(example = "Cartão de Crédito") String name) {
    }

    public static PreCheckoutResponse from(Cart cart) {
        List<ProductView> products = cart.items().stream()
                .map(p -> new ProductView(p.slug(), p.name(), p.description(), p.price().amount(),
                        p.price().currency()))
                .toList();
        List<PaymentMethodView> methods = cart.paymentMethods().stream()
                .map(m -> new PaymentMethodView(m.name()))
                .toList();
        return new PreCheckoutResponse(cart.id(), products, methods);
    }
}
