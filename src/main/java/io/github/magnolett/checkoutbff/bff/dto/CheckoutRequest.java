package io.github.magnolett.checkoutbff.bff.dto;

import io.github.magnolett.checkoutbff.checkout.application.PayCommand;
import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Checkout request. Card fields are validated by the cart, because whether a card is required depends on the
 * method chosen. Bean Validation covers only the structure.
 */
public record CheckoutRequest(@NotNull UUID cartId, @NotNull @Valid PaymentMethodRequest paymentMethod) {

    public record PaymentMethodRequest(
            @Schema(example = "Cartão de Crédito") @NotBlank @Size(max = 50) String name,
            @Schema(description = "Ausente no Pix", nullable = true) CardRequest card) {
    }

    public record CardRequest(
            @Schema(example = "Ana Souza") String holderName,
            @Schema(example = "4242424242424242") String number,
            @Schema(example = "12/28") String expiry,
            @Schema(example = "123") String cvv) {

        @Override
        public String toString() {
            return "CardRequest[masked]";
        }
    }

    public PayCommand toCommand(String idempotencyKey) {
        CardRequest card = paymentMethod.card();
        CardData cardData = card == null ? null
                : new CardData(card.holderName(), card.number(), card.expiry(), card.cvv());
        return new PayCommand(cartId, paymentMethod.name(), cardData, idempotencyKey);
    }
}
