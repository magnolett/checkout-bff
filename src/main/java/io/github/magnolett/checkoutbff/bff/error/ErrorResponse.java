package io.github.magnolett.checkoutbff.bff.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * The single error object of the contract. Every failure the frontend shows uses it, and every key is always
 * present (null when it does not apply), so the frontend parses one shape only.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Objeto único de erro, com o mesmo formato em toda falha")
public record ErrorResponse(
        @Schema(example = "BLOCK") DisplayType displayType,
        @Schema(example = "PAYMENT_DECLINED") String errorCode,
        @Schema(description = "Nulo quando a falha acontece antes de existir carrinho", nullable = true) UUID cartId,
        FrontendError frontendError) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FrontendError(
            @Schema(example = "P-1001") String code,
            @Schema(example = "Pagamento não aprovado") String title,
            String description,
            Button primaryButton,
            @Schema(nullable = true) Button secondaryButton) {
    }

    /** {@code url} is either a frontend route ("/checkout/pagamento") or an action token ("RETRY"). */
    public record Button(String label, String url) {
    }
}
