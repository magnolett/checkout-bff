package io.github.magnolett.checkoutbff.bff.error;

import io.github.magnolett.checkoutbff.bff.error.ErrorResponse.Button;
import io.github.magnolett.checkoutbff.bff.error.ErrorResponse.FrontendError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

/**
 * Error catalog: how each failure is presented to the frontend.
 *
 * <p>Business failures answer 4xx, as the challenge asks. Technical failures (a dependency down, an unexpected
 * error) answer 5xx with the same object: the frontend handles one format, and monitoring, SLOs and retry policies
 * keep telling a client mistake from an incident.
 */
public enum ErrorCode {

    PRODUCT_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "C-1001",
            "Plano indisponível",
            "O plano escolhido não está disponível. Escolha outro plano para continuar.",
            button("Ver planos", "/planos"), null),

    PRODUCTS_NOT_COMBINABLE(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "C-1005",
            "Planos não podem ser combinados",
            "Os planos escolhidos não podem ser assinados no mesmo pedido. Escolha um deles para continuar.",
            button("Ver planos", "/planos"), null),

    CART_NOT_FOUND(HttpStatus.NOT_FOUND, DisplayType.BLOCK, "C-1002",
            "Não encontramos seu pedido",
            "Este carrinho não existe ou já foi encerrado. Escolha seu plano novamente.",
            button("Escolher plano", "/planos"), null),

    CART_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "C-1003",
            "Seu pedido expirou",
            "Por segurança, o carrinho tem validade. Monte o pedido de novo para continuar.",
            button("Escolher plano", "/planos"), null),

    CART_ALREADY_PAID(HttpStatus.CONFLICT, DisplayType.BLOCK, "C-1004",
            "Este pedido já foi pago",
            "O pagamento deste carrinho já foi aprovado e a sua assinatura está ativa.",
            button("Acessar minha conta", "/minha-conta"), null),

    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "P-1001",
            "Pagamento não aprovado",
            "Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.",
            button("Tentar novamente", "RETRY"), button("Trocar meio de pagamento", "/checkout/pagamento")),

    PAYMENT_METHOD_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.INTERVENTION, "P-1002",
            "Meio de pagamento indisponível",
            "Escolha uma das opções de pagamento exibidas para este pedido.",
            button("Trocar meio de pagamento", "/checkout/pagamento"), null),

    INVALID_PAYMENT_DATA(HttpStatus.BAD_REQUEST, DisplayType.INTERVENTION, "P-1003",
            "Confira os dados do pagamento",
            "Alguns dados estão incompletos ou inválidos. Revise e tente novamente.",
            button("Revisar dados", "/checkout/pagamento"), null),

    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, DisplayType.INTERVENTION, "P-1004",
            "Pagamento em processamento",
            "Já estamos processando um pagamento deste pedido. Aguarde alguns segundos antes de tentar de novo.",
            button("Tentar novamente", "RETRY"), null),

    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.INTERVENTION, "P-1005",
            "Não foi possível confirmar o pagamento",
            "Recebemos dados diferentes para a mesma tentativa de pagamento. Revise e tente novamente.",
            button("Revisar dados", "/checkout/pagamento"), null),

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, DisplayType.BLOCK, "G-1001",
            "Não foi possível continuar",
            "Os dados enviados estão incompletos. Volte e tente novamente.",
            button("Voltar aos planos", "/planos"), null),

    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, DisplayType.BLOCK, "S-1001",
            "Serviço temporariamente indisponível",
            "Não conseguimos carregar as informações agora. Tente novamente em instantes.",
            button("Tentar novamente", "RETRY"), null),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, DisplayType.BLOCK, "S-1002",
            "Algo deu errado",
            "Tivemos um problema inesperado. Tente novamente em instantes.",
            button("Tentar novamente", "RETRY"), null);

    private final HttpStatus status;
    private final DisplayType displayType;
    private final String frontendCode;
    private final String title;
    private final String description;
    private final Button primaryButton;
    private final Button secondaryButton;

    ErrorCode(HttpStatus status, DisplayType displayType, String frontendCode, String title, String description,
              Button primaryButton, Button secondaryButton) {
        this.status = status;
        this.displayType = displayType;
        this.frontendCode = frontendCode;
        this.title = title;
        this.description = description;
        this.primaryButton = primaryButton;
        this.secondaryButton = secondaryButton;
    }

    /** Exhaustive mapping: a new business error that is not presented here does not compile. */
    public static ErrorCode of(CheckoutError error) {
        return switch (error) {
            case PRODUCT_NOT_FOUND -> PRODUCT_NOT_FOUND;
            case PRODUCTS_NOT_COMBINABLE -> PRODUCTS_NOT_COMBINABLE;
            case CART_NOT_FOUND -> CART_NOT_FOUND;
            case CART_EXPIRED -> CART_EXPIRED;
            case CART_ALREADY_PAID -> CART_ALREADY_PAID;
            case PAYMENT_METHOD_NOT_ALLOWED -> PAYMENT_METHOD_NOT_ALLOWED;
            case INVALID_PAYMENT_DATA -> INVALID_PAYMENT_DATA;
            case PAYMENT_DECLINED -> PAYMENT_DECLINED;
            case PAYMENT_IN_PROGRESS -> PAYMENT_IN_PROGRESS;
            case IDEMPOTENCY_KEY_REUSED -> IDEMPOTENCY_KEY_REUSED;
        };
    }

    public ErrorResponse toResponse(UUID cartId) {
        return new ErrorResponse(displayType, name(), cartId,
                new FrontendError(frontendCode, title, description, primaryButton, secondaryButton));
    }

    public ResponseEntity<ErrorResponse> toResponseEntity(UUID cartId) {
        return ResponseEntity.status(status).body(toResponse(cartId));
    }

    public HttpStatus status() {
        return status;
    }

    public DisplayType displayType() {
        return displayType;
    }

    public String frontendCode() {
        return frontendCode;
    }

    private static Button button(String label, String url) {
        return new Button(label, url);
    }
}
