package io.github.magnolett.checkoutbff.bff;

import io.github.magnolett.checkoutbff.bff.dto.CheckoutRequest;
import io.github.magnolett.checkoutbff.bff.dto.CheckoutResponse;
import io.github.magnolett.checkoutbff.bff.dto.PreCheckoutRequest;
import io.github.magnolett.checkoutbff.bff.dto.PreCheckoutResponse;
import io.github.magnolett.checkoutbff.bff.error.ApiExceptionHandler;
import io.github.magnolett.checkoutbff.bff.error.ErrorResponse;
import io.github.magnolett.checkoutbff.bff.error.InvalidRequestException;
import io.github.magnolett.checkoutbff.checkout.application.CheckoutService;
import io.github.magnolett.checkoutbff.checkout.application.PreCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * HTTP entry point of the BFF. It only translates the screen contract to commands and back: no business rule here,
 * and no knowledge of where products or payment methods come from.
 */
@RestController
@RequestMapping(value = "/operations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Pré-checkout e checkout de assinatura")
public class CheckoutController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_\\-:.]{1,100}");

    private final PreCheckoutService preCheckoutService;
    private final CheckoutService checkoutService;

    @PostMapping("/pre-checkout")
    @Operation(summary = "Monta o carrinho para a tela de pagamento",
            description = "Consulta, em paralelo, o serviço de produtos e o de meios de pagamento.")
    @ApiResponse(responseCode = "200", description = "Carrinho criado",
            content = @Content(examples = @ExampleObject(value = ApiExamples.PRE_CHECKOUT_OK)))
    @ApiResponse(responseCode = "422", description = "Produto inexistente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.PRODUCT_NOT_FOUND)))
    @ApiResponse(responseCode = "503", description = "Serviço de produtos ou de meios fora do ar",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.SERVICE_UNAVAILABLE)))
    public Mono<PreCheckoutResponse> preCheckout(@Valid @RequestBody PreCheckoutRequest request) {
        return preCheckoutService.start(request.normalizedSlugs())
                .map(PreCheckoutResponse::from);
    }

    @PostMapping("/checkout")
    @Operation(summary = "Conclui o pagamento do carrinho",
            description = "Cobrança simulada. Cartão terminado em 0002 é recusado; Pix não leva dados de cartão.")
    @ApiResponse(responseCode = "200", description = "Pagamento aprovado",
            content = @Content(examples = {
                    @ExampleObject(name = "cartão", value = ApiExamples.CHECKOUT_CARD_OK),
                    @ExampleObject(name = "pix", value = ApiExamples.CHECKOUT_PIX_OK)}))
    @ApiResponse(responseCode = "400", description = "Dados de pagamento inválidos",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.INVALID_PAYMENT_DATA)))
    @ApiResponse(responseCode = "404", description = "Carrinho inexistente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.CART_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "Carrinho já pago ou pagamento em andamento",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.CART_ALREADY_PAID)))
    @ApiResponse(responseCode = "422", description = "Pagamento recusado ou meio não ofertado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.PAYMENT_DECLINED)))
    public Mono<CheckoutResponse> checkout(
            @Valid @RequestBody CheckoutRequest request,
            @Parameter(description = "Opcional. A mesma chave repete o resultado original sem cobrar de novo.")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Parameter(hidden = true) ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiExceptionHandler.CART_ID_ATTRIBUTE, request.cartId());
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidRequestException("malformed Idempotency-Key header");
        }
        return checkoutService.pay(request.toCommand(idempotencyKey))
                .map(CheckoutResponse::from);
    }
}
