package io.github.magnolett.checkoutbff.bff;

import io.github.magnolett.checkoutbff.bff.error.FallbackErrorHandler;
import io.github.magnolett.checkoutbff.checkout.application.CheckoutService;
import io.github.magnolett.checkoutbff.checkout.application.PayCommand;
import io.github.magnolett.checkoutbff.checkout.application.PaymentReceipt;
import io.github.magnolett.checkoutbff.checkout.application.PreCheckoutService;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(CheckoutController.class)
@Import(FallbackErrorHandler.class)
@ExtendWith(OutputCaptureExtension.class)
class CheckoutControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String FULL_NUMBER = "4111111111114242";
    private static final String SECRET_CVV = "7391";

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private PreCheckoutService preCheckoutService;

    @MockitoBean
    private CheckoutService checkoutService;

    private final UUID cartId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        client = client.mutate().responseTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    // ---- helpers

    private WebTestClient.ResponseSpec post(String path, String body) {
        return post(path, MediaType.APPLICATION_JSON, body, null);
    }

    private WebTestClient.ResponseSpec post(String path, MediaType contentType, String body, String idempotencyKey) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(contentType);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.bodyValue(body).exchange();
    }

    private static String preCheckoutBody(String... slugs) {
        StringBuilder items = new StringBuilder();
        for (String slug : slugs) {
            items.append(items.length() == 0 ? "" : ",").append("{\"slug\":\"").append(slug).append("\"}");
        }
        return "{\"products\":[" + items + "]}";
    }

    private String cardCheckoutBody() {
        return checkoutBody(cartId, CREDIT, cardJson(FULL_NUMBER, "12/28", SECRET_CVV));
    }

    private static String cardJson(String number, String expiry, String cvv) {
        return "{\"holderName\":\"Ana Souza\",\"number\":\"" + number + "\",\"expiry\":\"" + expiry
                + "\",\"cvv\":\"" + cvv + "\"}";
    }

    private static String checkoutBody(UUID cartId, String method, String cardJson) {
        String card = cardJson == null ? "" : ",\"card\":" + cardJson;
        return "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"" + method + "\"" + card + "}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(WebTestClient.ResponseSpec spec, int status, String contentType) {
        String raw = spec.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.valueOf(contentType))
                .expectBody(String.class).returnResult().getResponseBody();
        return MAPPER.readValue(raw, Map.class);
    }

    private static Map<String, Object> readJson(WebTestClient.ResponseSpec spec, int status) {
        return read(spec, status, MediaType.APPLICATION_JSON_VALUE);
    }

    private static String rawBody(WebTestClient.ResponseSpec spec, int status) {
        return spec.expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** Every key of the error object must be present, whatever its value. */
    private static void assertErrorObject(Map<String, Object> body, String errorCode, UUID expectedCartId) {
        assertThat(body).containsOnlyKeys("displayType", "errorCode", "cartId", "frontendError");
        assertThat(body.get("displayType")).isIn("BLOCK", "INTERVENTION");
        assertThat(body.get("errorCode")).isEqualTo(errorCode);
        assertThat(body.get("cartId")).isEqualTo(expectedCartId == null ? null : expectedCartId.toString());

        Map<String, Object> frontend = map(body.get("frontendError"));
        assertThat(frontend).containsOnlyKeys("code", "title", "description", "primaryButton", "secondaryButton");
        assertThat((String) frontend.get("code")).isNotBlank();
        assertThat((String) frontend.get("title")).isNotBlank();
        assertThat((String) frontend.get("description")).isNotBlank();
        Map<String, Object> primary = map(frontend.get("primaryButton"));
        assertThat(primary).containsOnlyKeys("label", "url");
        assertThat((String) primary.get("label")).isNotBlank();
        assertThat((String) primary.get("url")).isNotBlank();
        if (frontend.get("secondaryButton") != null) {
            assertThat(map(frontend.get("secondaryButton"))).containsOnlyKeys("label", "url");
        }
    }

    private PaymentReceipt cardReceipt() {
        return new PaymentReceipt(cartId, NOW, PaymentMethodType.CREDIT_CARD, CREDIT, "4242", null,
                Money.of("39.90", "BRL"), List.of(premium()));
    }

    private PaymentReceipt pixReceipt() {
        return new PaymentReceipt(cartId, NOW, PaymentMethodType.PIX, PIX, null, "E99999999202610141600ABCDEFGHIJK",
                Money.of("39.90", "BRL"), List.of(premium()));
    }

    // ---- pre-checkout: success contract

    @Test
    void preCheckoutAnswersTheCartForThePaymentScreenWithTheExactShape() {
        var cart = openCart();
        when(preCheckoutService.start(any())).thenReturn(Mono.just(cart));

        String raw = rawBody(post("/operations/pre-checkout", preCheckoutBody("premium")), 200);
        Map<String, Object> body = MAPPER.readValue(raw, Map.class);

        assertThat(body).containsOnlyKeys("cartId", "products", "paymentMethods");
        assertThat(body.get("cartId")).isEqualTo(cart.id().toString());
        List<Object> products = (List<Object>) body.get("products");
        assertThat(products).hasSize(1);
        assertThat(map(products.get(0))).containsOnlyKeys("slug", "name", "description", "price", "currency")
                .containsEntry("slug", "premium")
                .containsEntry("name", "Plano Premium")
                .containsEntry("description", "Assinatura mensal com catálogo completo")
                .containsEntry("currency", "BRL");
        assertThat(raw).contains("\"price\":39.90");
        List<Object> methods = (List<Object>) body.get("paymentMethods");
        assertThat(methods).hasSize(3);
        assertThat(methods).allSatisfy(method -> assertThat(map(method)).containsOnlyKeys("name"));
        assertThat(methods).extracting(m -> map(m).get("name")).containsExactly(CREDIT, DEBIT, PIX);
    }

    @Test
    void preCheckoutNormalizesSlugsBeforeCallingTheService() {
        when(preCheckoutService.start(any())).thenReturn(Mono.just(openCart()));

        post("/operations/pre-checkout", preCheckoutBody(" Premium ", "BASICO")).expectStatus().isOk();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> slugs = ArgumentCaptor.forClass(List.class);
        verify(preCheckoutService).start(slugs.capture());
        assertThat(slugs.getValue()).containsExactly("premium", "basico");
    }

    // ---- pre-checkout: errors

    @Test
    void unknownProductAnswers422WithTheErrorObjectAndNoCartId() {
        when(preCheckoutService.start(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.PRODUCT_NOT_FOUND, null, "slug=ghost")));

        Map<String, Object> body = readJson(post("/operations/pre-checkout", preCheckoutBody("ghost")), 422);

        assertErrorObject(body, "PRODUCT_NOT_FOUND", null);
    }

    @Test
    void preCheckoutDependencyDownAnswers503WithoutCartId() {
        when(preCheckoutService.start(any()))
                .thenReturn(Mono.error(new ServiceUnavailableException("products-service", "timeout")));

        Map<String, Object> body = readJson(post("/operations/pre-checkout", preCheckoutBody("premium")), 503);

        assertErrorObject(body, "SERVICE_UNAVAILABLE", null);
    }

    @Test
    void preCheckoutUnexpectedFailureAnswers500() {
        when(preCheckoutService.start(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        Map<String, Object> body = readJson(post("/operations/pre-checkout", preCheckoutBody("premium")), 500);

        assertErrorObject(body, "INTERNAL_ERROR", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"products\":null}",
            "{\"products\":[]}",
            "{\"products\":[{}]}",
            "{\"products\":[null]}",
            "{\"products\":[{\"slug\":\"\"}]}",
            "{\"products\":[{\"slug\":\"   \"}]}",
            "{\"products\":[{\"slug\":\"pre mium\"}]}",
            "{\"products\":[{\"slug\":\"premium;drop\"}]}",
            "{\"products\":[{\"slug\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]}"
    })
    void preCheckoutBeanValidationFailureAnswers400InvalidRequest(String body) {
        Map<String, Object> response = readJson(post("/operations/pre-checkout", body), 400);

        assertErrorObject(response, "INVALID_REQUEST", null);
        verifyNoInteractions(preCheckoutService);
    }

    @Test
    void preCheckoutWithMoreThanTenProductsAnswers400() {
        String[] slugs = new String[11];
        java.util.Arrays.fill(slugs, "premium");

        Map<String, Object> response = readJson(post("/operations/pre-checkout", preCheckoutBody(slugs)), 400);

        assertErrorObject(response, "INVALID_REQUEST", null);
        verifyNoInteractions(preCheckoutService);
    }

    @Test
    void duplicatedSlugAnswers400InvalidRequest() {
        Map<String, Object> response = readJson(post("/operations/pre-checkout", preCheckoutBody("premium", "premium")),
                400);

        assertErrorObject(response, "INVALID_REQUEST", null);
        verifyNoInteractions(preCheckoutService);
    }

    @Test
    void duplicatedSlugThatDiffersOnlyByCaseAndSpacesIsStillDuplicated() {
        Map<String, Object> response = readJson(
                post("/operations/pre-checkout", preCheckoutBody("premium", " PREMIUM ")), 400);

        assertErrorObject(response, "INVALID_REQUEST", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"products\":[{\"slug\":\"premium\"}", "not json", "", "{\"products\":\"premium\"}",
            "[]"})
    void malformedJsonAnswers400InvalidRequest(String body) {
        Map<String, Object> response = readJson(post("/operations/pre-checkout", body), 400);

        assertErrorObject(response, "INVALID_REQUEST", null);
    }

    @Test
    void wrongContentTypeAnswers415WithTheSameErrorObject() {
        Map<String, Object> body = readJson(
                post("/operations/pre-checkout", MediaType.TEXT_PLAIN, preCheckoutBody("premium"), null), 415);

        assertErrorObject(body, "INVALID_REQUEST", null);
        verifyNoInteractions(preCheckoutService);
    }

    @Test
    void wrongHttpMethodAnswers405WithTheSameErrorObject() {
        Map<String, Object> body = readJson(client.get().uri("/operations/pre-checkout").exchange(), 405);

        assertErrorObject(body, "INVALID_REQUEST", null);
    }

    @Test
    void unknownRouteKeepsTheErrorObject() {
        Map<String, Object> body = readJson(client.get().uri("/operations/nowhere").exchange(), 404);

        assertThat(body).containsOnlyKeys("displayType", "errorCode", "cartId", "frontendError");
    }

    // ---- checkout: success contract

    @Test
    void cardCheckoutAnswersTheSuccessScreenWithTheExactShape() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(cardReceipt()));

        String raw = rawBody(post("/operations/checkout", cardCheckoutBody()), 200);
        Map<String, Object> body = MAPPER.readValue(raw, Map.class);

        assertThat(body).containsOnlyKeys("cartId", "status", "paidAt", "payment", "products");
        assertThat(body.get("cartId")).isEqualTo(cartId.toString());
        assertThat(body.get("status")).isEqualTo("APROVADO");
        assertThat(body.get("paidAt")).isEqualTo("2026-10-14T16:00:00Z");
        Map<String, Object> payment = map(body.get("payment"));
        assertThat(payment).containsOnlyKeys("method", "lastDigits", "pixCode", "amount", "currency");
        assertThat(payment.get("method")).isEqualTo(CREDIT);
        assertThat(payment.get("lastDigits")).isEqualTo("4242");
        assertThat(payment.get("pixCode")).isNull();
        assertThat(payment.get("currency")).isEqualTo("BRL");
        assertThat(raw).contains("\"amount\":39.90");
        List<Object> products = (List<Object>) body.get("products");
        assertThat(products).hasSize(1);
        assertThat(map(products.get(0))).containsOnlyKeys("slug", "name", "price")
                .containsEntry("slug", "premium")
                .containsEntry("name", "Plano Premium");
    }

    @Test
    void pixCheckoutHasNullLastDigitsAndThePixCode() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(pixReceipt()));

        Map<String, Object> body = readJson(post("/operations/checkout", checkoutBody(cartId, PIX, null)), 200);

        Map<String, Object> payment = map(body.get("payment"));
        assertThat(payment).containsOnlyKeys("method", "lastDigits", "pixCode", "amount", "currency");
        assertThat(payment.get("method")).isEqualTo(PIX);
        assertThat(payment.get("lastDigits")).isNull();
        assertThat(payment.get("pixCode")).isEqualTo("E99999999202610141600ABCDEFGHIJK");
    }

    @Test
    void theRequestIsTranslatedToACommandWithTheCardAndNoKey() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(cardReceipt()));

        post("/operations/checkout", checkoutBody(cartId, CREDIT, cardJson("4111 1111 1111 4242", "12/28", "123")))
                .expectStatus().isOk();

        ArgumentCaptor<PayCommand> captor = ArgumentCaptor.forClass(PayCommand.class);
        verify(checkoutService).pay(captor.capture());
        PayCommand command = captor.getValue();
        assertThat(command.cartId()).isEqualTo(cartId);
        assertThat(command.methodName()).isEqualTo(CREDIT);
        assertThat(command.idempotencyKey()).isNull();
        assertThat(command.card().holderName()).isEqualTo("Ana Souza");
        assertThat(command.card().number()).isEqualTo("4111111111114242");
        assertThat(command.card().expiry()).isEqualTo("12/28");
        assertThat(command.card().cvv()).isEqualTo("123");
    }

    @Test
    void pixRequestHasNoCardInTheCommand() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(pixReceipt()));

        post("/operations/checkout", checkoutBody(cartId, PIX, null)).expectStatus().isOk();

        ArgumentCaptor<PayCommand> captor = ArgumentCaptor.forClass(PayCommand.class);
        verify(checkoutService).pay(captor.capture());
        assertThat(captor.getValue().card()).isNull();
        assertThat(captor.getValue().methodName()).isEqualTo(PIX);
    }

    @Test
    void theIdempotencyKeyHeaderReachesTheService() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(cardReceipt()));

        post("/operations/checkout", MediaType.APPLICATION_JSON, cardCheckoutBody(), "order-42_a:b.c-d")
                .expectStatus().isOk();

        ArgumentCaptor<PayCommand> captor = ArgumentCaptor.forClass(PayCommand.class);
        verify(checkoutService).pay(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("order-42_a:b.c-d");
    }

    @Test
    void anIdempotencyKeyOfOneHundredAllowedCharactersIsAccepted() {
        when(checkoutService.pay(any())).thenReturn(Mono.just(cardReceipt()));

        post("/operations/checkout", MediaType.APPLICATION_JSON, cardCheckoutBody(), "k".repeat(100))
                .expectStatus().isOk();
    }

    // ---- checkout: business errors

    @ParameterizedTest
    @EnumSource(CheckoutError.class)
    void everyBusinessErrorAnswers4xxWithTheErrorObjectAndTheCartId(CheckoutError error) {
        boolean preCheckoutError = error == CheckoutError.PRODUCT_NOT_FOUND
                || error == CheckoutError.PRODUCTS_NOT_COMBINABLE;
        UUID errorCartId = preCheckoutError ? null : cartId;
        when(checkoutService.pay(any())).thenReturn(Mono.error(new CheckoutException(error, errorCartId, "detail")));

        WebTestClient.ResponseSpec spec = post("/operations/checkout", cardCheckoutBody());

        String raw = spec.expectStatus().is4xxClientError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        assertErrorObject(MAPPER.readValue(raw, Map.class), error.name(), errorCartId);
    }

    @Test
    void paymentDeclinedMatchesTheExampleOfTheChallenge() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.PAYMENT_DECLINED, cartId, "declined")));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 422);

        assertErrorObject(body, "PAYMENT_DECLINED", cartId);
        assertThat(body.get("displayType")).isEqualTo("BLOCK");
        Map<String, Object> frontend = map(body.get("frontendError"));
        assertThat(frontend.get("code")).isEqualTo("P-1001");
        assertThat(frontend.get("title")).isEqualTo("Pagamento não aprovado");
        assertThat(frontend.get("description"))
                .isEqualTo("Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.");
        assertThat(map(frontend.get("primaryButton"))).containsEntry("label", "Tentar novamente")
                .containsEntry("url", "RETRY");
        assertThat(map(frontend.get("secondaryButton"))).containsEntry("label", "Trocar meio de pagamento")
                .containsEntry("url", "/checkout/pagamento");
    }

    @Test
    void cartNotFoundAnswers404() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.CART_NOT_FOUND, cartId)));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 404);

        assertErrorObject(body, "CART_NOT_FOUND", cartId);
    }

    @Test
    void secondaryButtonIsPresentAsNullWhenThereIsNone() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.CART_ALREADY_PAID, cartId)));

        String raw = rawBody(post("/operations/checkout", cardCheckoutBody()), 409);

        assertThat(raw).contains("\"secondaryButton\":null");
        assertErrorObject(MAPPER.readValue(raw, Map.class), "CART_ALREADY_PAID", cartId);
    }

    @Test
    void anErrorWithoutCartIdSerializesTheKeyAsNull() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.PRODUCT_NOT_FOUND, null)));

        String raw = rawBody(post("/operations/checkout", cardCheckoutBody()), 422);

        assertThat(raw).contains("\"cartId\":null");
    }

    // ---- checkout: technical errors

    @Test
    void serviceUnavailableAnswers503WithTheCartIdOfTheRequest() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new ServiceUnavailableException("payment-gateway", "no answer")));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 503);

        assertErrorObject(body, "SERVICE_UNAVAILABLE", cartId);
    }

    @Test
    void aDatabaseOutageAnswers503() {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("mongo down")));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 503);

        assertErrorObject(body, "SERVICE_UNAVAILABLE", cartId);
    }

    @Test
    void anUnexpectedExceptionAnswers500InternalErrorWithTheCartId() {
        when(checkoutService.pay(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 500);

        assertErrorObject(body, "INTERNAL_ERROR", cartId);
    }

    @Test
    void anExceptionThrownSynchronouslyByTheServiceAlsoAnswers500() {
        when(checkoutService.pay(any())).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> body = readJson(post("/operations/checkout", cardCheckoutBody()), 500);

        assertErrorObject(body, "INTERNAL_ERROR", cartId);
    }

    @Test
    void internalErrorsNeverLeakTheExceptionMessage() {
        when(checkoutService.pay(any())).thenReturn(Mono.error(new IllegalStateException("secret-internal-detail")));

        String raw = rawBody(post("/operations/checkout", cardCheckoutBody()), 500);

        assertThat(raw).doesNotContain("secret-internal-detail").doesNotContain("IllegalStateException");
    }

    // ---- checkout: invalid requests

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"paymentMethod\":{\"name\":\"Pix\"}}",
            "{\"cartId\":\"not-a-uuid\",\"paymentMethod\":{\"name\":\"Pix\"}}",
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\"}",
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"paymentMethod\":{}}",
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"paymentMethod\":{\"name\":\"  \"}}",
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"paymentMethod\":{\"name\":"
                    + "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}",
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"paymentMethod\":{\"name\":\"Pix\"",
    })
    void checkoutWithAnInvalidBodyAnswers400InvalidRequest(String body) {
        Map<String, Object> response = readJson(post("/operations/checkout", body), 400);

        assertErrorObject(response, "INVALID_REQUEST", null);
        verifyNoInteractions(checkoutService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "bad!key", "slash/key", ""})
    void aMalformedIdempotencyKeyAnswers400WithTheCartIdOfTheRequest(String key) {
        Map<String, Object> body = readJson(
                post("/operations/checkout", MediaType.APPLICATION_JSON, cardCheckoutBody(), key), 400);

        assertErrorObject(body, "INVALID_REQUEST", cartId);
        verifyNoInteractions(checkoutService);
    }

    @Test
    void anIdempotencyKeyOfOneHundredAndOneCharactersAnswers400() {
        Map<String, Object> body = readJson(
                post("/operations/checkout", MediaType.APPLICATION_JSON, cardCheckoutBody(), "k".repeat(101)), 400);

        assertErrorObject(body, "INVALID_REQUEST", cartId);
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutWithWrongContentTypeAnswers415WithTheSameErrorObject() {
        Map<String, Object> body = readJson(
                post("/operations/checkout", MediaType.TEXT_PLAIN, cardCheckoutBody(), null), 415);

        assertErrorObject(body, "INVALID_REQUEST", null);
        verifyNoInteractions(checkoutService);
    }

    @Test
    void anUnacceptableAcceptHeaderKeepsTheErrorObject() {
        Map<String, Object> body = read(client.post().uri("/operations/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_XML_VALUE)
                .bodyValue(cardCheckoutBody()).exchange(), 406, MediaType.APPLICATION_JSON_VALUE);

        assertErrorObject(body, "INVALID_REQUEST", null);
    }

    // ---- logs

    @Test
    void successNeverLogsTheCardNumberOrTheCvv(CapturedOutput output) {
        when(checkoutService.pay(any())).thenReturn(Mono.just(cardReceipt()));

        post("/operations/checkout", cardCheckoutBody()).expectStatus().isOk();

        assertNoCardDataIn(output);
    }

    @Test
    void aBusinessFailureLogsAndNeverLogsTheCardNumberOrTheCvv(CapturedOutput output) {
        when(checkoutService.pay(any()))
                .thenReturn(Mono.error(new CheckoutException(CheckoutError.PAYMENT_DECLINED, cartId, "declined")));

        post("/operations/checkout", cardCheckoutBody()).expectStatus().isEqualTo(422);

        assertThat(output.getAll()).contains("PAYMENT_DECLINED").contains(cartId.toString());
        assertNoCardDataIn(output);
    }

    @Test
    void anUnexpectedFailureNeverLogsTheCardNumberOrTheCvv(CapturedOutput output) {
        when(checkoutService.pay(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        post("/operations/checkout", cardCheckoutBody()).expectStatus().isEqualTo(500);

        assertThat(output.getAll()).contains("unexpected failure");
        assertNoCardDataIn(output);
    }

    @Test
    void aValidationFailureNeverLogsTheRejectedCardData(CapturedOutput output) {
        String body = "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\" \",\"card\":"
                + cardJson(FULL_NUMBER, "12/28", SECRET_CVV) + "}}";

        post("/operations/checkout", body).expectStatus().isEqualTo(400);

        assertThat(output.getAll()).contains("invalid request");
        assertNoCardDataIn(output);
    }

    @Test
    void malformedJsonContainingACardNeverLogsTheCardData(CapturedOutput output) {
        String broken = "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"Cartão de Crédito\",\"card\":"
                + cardJson(FULL_NUMBER, "12/28", SECRET_CVV);

        post("/operations/checkout", broken).expectStatus().isEqualTo(400);

        assertNoCardDataIn(output);
    }

    @Test
    void aMalformedIdempotencyKeyNeverLogsTheCardData(CapturedOutput output) {
        post("/operations/checkout", MediaType.APPLICATION_JSON, cardCheckoutBody(), "bad key").expectStatus()
                .isEqualTo(400);

        assertThat(output.getAll()).contains("invalid request");
        assertNoCardDataIn(output);
    }

    @Test
    void aWrongContentTypeNeverLogsTheCardData(CapturedOutput output) {
        post("/operations/checkout", MediaType.TEXT_PLAIN, cardCheckoutBody(), null).expectStatus().isEqualTo(415);

        assertNoCardDataIn(output);
    }

    private static void assertNoCardDataIn(CapturedOutput output) {
        assertThat(output.getAll())
                .doesNotContain(FULL_NUMBER)
                .doesNotContain("4111 1111 1111 4242")
                .doesNotContain("411111111111")
                .doesNotContain(SECRET_CVV);
    }
}
