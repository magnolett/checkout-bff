package io.github.magnolett.checkoutbff.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.magnolett.checkoutbff.checkout.application.PaymentReceipt;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Success screen. {@code lastDigits} (card) and {@code pixCode} (Pix) are both always present, one of them null,
 * so the frontend types the payment once.
 */
public record CheckoutResponse(
        UUID cartId,
        @Schema(example = "APROVADO") String status,
        @Schema(example = "2026-10-14T16:00:00Z") Instant paidAt,
        PaymentView payment,
        List<PaidProductView> products) {

    static final String APPROVED = "APROVADO";

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaymentView(
            @Schema(example = "Cartão de Crédito") String method,
            @Schema(example = "4242", nullable = true) String lastDigits,
            @Schema(description = "endToEndId simulado do Pix", example = "E99999999202610141600ABCDEFGHIJK",
                    nullable = true) String pixCode,
            @Schema(example = "39.90") BigDecimal amount,
            @Schema(example = "BRL") String currency) {
    }

    public record PaidProductView(
            @Schema(example = "premium") String slug,
            @Schema(example = "Plano Premium") String name,
            @Schema(example = "39.90") BigDecimal price) {
    }

    public static CheckoutResponse from(PaymentReceipt receipt) {
        PaymentView payment = new PaymentView(receipt.methodName(), receipt.lastDigits(), receipt.pixCode(),
                receipt.amount().amount(), receipt.amount().currency());
        List<PaidProductView> products = receipt.products().stream()
                .map(p -> new PaidProductView(p.slug(), p.name(), p.price().amount()))
                .toList();
        return new CheckoutResponse(receipt.cartId(), APPROVED, receipt.paidAt(), payment, products);
    }
}
