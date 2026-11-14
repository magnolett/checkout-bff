package io.github.magnolett.checkoutbff.bff.error;

import io.github.magnolett.checkoutbff.bff.error.ErrorResponse.Button;
import io.github.magnolett.checkoutbff.bff.error.ErrorResponse.FrontendError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    private static final Set<ErrorCode> TECHNICAL = EnumSet.of(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR);

    @ParameterizedTest
    @EnumSource(CheckoutError.class)
    void everyBusinessErrorOfTheDomainHasAFrontendPresentation(CheckoutError error) {
        ErrorCode code = ErrorCode.of(error);

        assertThat(code).isNotNull();
        assertThat(code.name()).isEqualTo(error.name());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeFillsTheWholeFrontendErrorContract(ErrorCode code) {
        UUID cartId = UUID.randomUUID();

        ErrorResponse response = code.toResponse(cartId);

        assertThat(response.displayType()).isNotNull();
        assertThat(response.errorCode()).isEqualTo(code.name());
        assertThat(response.cartId()).isEqualTo(cartId);
        FrontendError frontend = response.frontendError();
        assertThat(frontend).isNotNull();
        assertThat(frontend.code()).isNotBlank();
        assertThat(frontend.title()).isNotBlank();
        assertThat(frontend.description()).isNotBlank();
        assertThat(frontend.primaryButton()).isNotNull();
        assertNonBlank(frontend.primaryButton());
        if (frontend.secondaryButton() != null) {
            assertNonBlank(frontend.secondaryButton());
        }
    }

    private static void assertNonBlank(Button button) {
        assertThat(button.label()).isNotBlank();
        assertThat(button.url()).isNotBlank();
    }

    @Test
    void cartIdMayBeAbsentAndTheRestOfTheObjectIsUnchanged() {
        ErrorResponse response = ErrorCode.PRODUCT_NOT_FOUND.toResponse(null);

        assertThat(response.cartId()).isNull();
        assertThat(response.frontendError()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND.toResponse(UUID.randomUUID())
                .frontendError());
    }

    @Test
    void frontendCodesAreUnique() {
        var codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::frontendCode).collect(Collectors.toList());

        assertThat(codes).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void businessFailuresAreFourHundredsAndTechnicalOnesFiveHundreds(ErrorCode code) {
        if (TECHNICAL.contains(code)) {
            assertThat(code.status().is5xxServerError()).as(code.name()).isTrue();
        } else {
            assertThat(code.status().is4xxClientError()).as(code.name()).isTrue();
        }
    }

    @Test
    void technicalFailuresUseTheStandardStatuses() {
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorCode.INVALID_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void everyBusinessErrorOfTheDomainIsA4xx() {
        for (CheckoutError error : CheckoutError.values()) {
            assertThat(ErrorCode.of(error).status().is4xxClientError()).as(error.name()).isTrue();
        }
    }

    @Test
    void paymentDeclinedMatchesTheExampleOfTheChallenge() {
        UUID cartId = UUID.randomUUID();

        ErrorResponse response = ErrorCode.of(CheckoutError.PAYMENT_DECLINED).toResponse(cartId);

        assertThat(response).isEqualTo(new ErrorResponse(DisplayType.BLOCK, "PAYMENT_DECLINED", cartId,
                new FrontendError("P-1001", "Pagamento não aprovado",
                        "Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.",
                        new Button("Tentar novamente", "RETRY"),
                        new Button("Trocar meio de pagamento", "/checkout/pagamento"))));
    }

    @Test
    void theResponseEntityCarriesTheStatusAndTheSameObject() {
        UUID cartId = UUID.randomUUID();

        ResponseEntity<ErrorResponse> entity = ErrorCode.CART_ALREADY_PAID.toResponseEntity(cartId);

        assertThat(entity.getStatusCode()).isEqualTo(ErrorCode.CART_ALREADY_PAID.status());
        assertThat(entity.getBody()).isEqualTo(ErrorCode.CART_ALREADY_PAID.toResponse(cartId));
    }

    @Test
    void displayTypeIsBlockForScreenErrorsAndInterventionForWarningsThatKeepTheFlow() {
        assertThat(ErrorCode.PAYMENT_DECLINED.displayType()).isEqualTo(DisplayType.BLOCK);
        assertThat(ErrorCode.CART_ALREADY_PAID.displayType()).isEqualTo(DisplayType.BLOCK);
        assertThat(ErrorCode.INVALID_PAYMENT_DATA.displayType()).isEqualTo(DisplayType.INTERVENTION);
        assertThat(ErrorCode.PAYMENT_IN_PROGRESS.displayType()).isEqualTo(DisplayType.INTERVENTION);
    }
}
