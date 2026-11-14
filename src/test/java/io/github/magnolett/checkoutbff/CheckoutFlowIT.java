package io.github.magnolett.checkoutbff;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.jayway.jsonpath.JsonPath;
import io.github.magnolett.checkoutbff.support.IntegrationTestBase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole path with real infrastructure: HTTP, the two external services, MongoDB, the scheduled outbox relay,
 * Kafka and the subscription consumer. The context is closed after the class so its relay stops polling.
 */
@AutoConfigureWebTestClient(timeout = "20s")
@DirtiesContext
@TestPropertySource(properties = {
        "messaging.outbox.relay-interval=200ms",
        "integration.payment-gateway.latency=300ms"})
class CheckoutFlowIT extends IntegrationTestBase {

    private static final String CREDIT_CARD = "Cartão de Crédito";
    private static final String APPROVED_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";

    @Autowired
    private WebTestClient http;

    @Autowired
    private ReactiveMongoTemplate mongo;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Value("${local.server.port}")
    private int port;

    @Test
    void preCheckoutReturnsTheCartReadyForThePaymentScreen() {
        String body = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"premium\"},{\"slug\":\"basico\"}]}",
                null, 200);

        assertThat(UUID.fromString(JsonPath.read(body, "$.cartId"))).isNotNull();
        assertThat(JsonPath.<List<String>>read(body, "$.products[*].slug")).containsExactly("premium", "basico");
        assertThat(JsonPath.<String>read(body, "$.products[0].name")).isEqualTo("Plano Premium");
        assertThat(JsonPath.<String>read(body, "$.products[0].description"))
                .isEqualTo("Assinatura mensal com catálogo completo");
        assertThat(JsonPath.<Double>read(body, "$.products[0].price")).isEqualTo(39.90);
        assertThat(JsonPath.<String>read(body, "$.products[0].currency")).isEqualTo("BRL");
        assertThat(JsonPath.<List<String>>read(body, "$.paymentMethods[*].name"))
                .containsExactly(CREDIT_CARD, "Cartão de Débito", "Pix");
    }

    @Test
    void approvedCardPaymentActivatesTheSubscriptionThroughKafka() {
        String cartId = newCart("premium");

        String body = post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), null, 200);

        assertThat(JsonPath.<String>read(body, "$.cartId")).isEqualTo(cartId);
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("APROVADO");
        assertThat(JsonPath.<String>read(body, "$.paidAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        Map<String, Object> payment = JsonPath.read(body, "$.payment");
        assertThat(payment).containsEntry("method", CREDIT_CARD).containsEntry("lastDigits", "4242")
                .containsEntry("pixCode", null).containsEntry("amount", 39.90).containsEntry("currency", "BRL");
        assertThat(JsonPath.<List<String>>read(body, "$.products[*].slug")).containsExactly("premium");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document subscription = mongo.findById(cartId, Document.class, "subscriptions").block();
            assertThat(subscription).isNotNull();
            assertThat(subscription.getString("status")).isEqualTo("ACTIVE");
            assertThat(subscription.getList("products", String.class)).containsExactly("premium");
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rawCart(cartId).getList("outbox", Document.class)).isEmpty());
    }

    @Test
    void storedCartKeepsOnlyTheLastDigits() {
        String cartId = newCart("premium");
        post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), null, 200);

        Document cart = rawCart(cartId);
        assertThat(cart.toJson()).doesNotContain(APPROVED_CARD);
        Document attempt = cart.getList("attempts", Document.class).getFirst();
        assertThat(attempt.getString("lastDigits")).isEqualTo("4242");
        assertThat(attempt.keySet()).doesNotContain("number", "cvv", "card", "holderName");
    }

    @Test
    void declinedCardShowsTheErrorScreenAndTheCartStaysPayable() {
        String cartId = newCart("premium");

        String declined = post("/operations/checkout", cardPayment(cartId, DECLINED_CARD), null, 422);

        assertThat(JsonPath.<String>read(declined, "$.displayType")).isEqualTo("BLOCK");
        assertThat(JsonPath.<String>read(declined, "$.errorCode")).isEqualTo("PAYMENT_DECLINED");
        assertThat(JsonPath.<String>read(declined, "$.cartId")).isEqualTo(cartId);
        assertThat(JsonPath.<String>read(declined, "$.frontendError.code")).isEqualTo("P-1001");
        assertThat(JsonPath.<String>read(declined, "$.frontendError.title")).isEqualTo("Pagamento não aprovado");
        assertThat(JsonPath.<String>read(declined, "$.frontendError.description"))
                .isEqualTo("Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.");
        assertThat(JsonPath.<String>read(declined, "$.frontendError.primaryButton.url")).isEqualTo("RETRY");
        assertThat(JsonPath.<String>read(declined, "$.frontendError.secondaryButton.url"))
                .isEqualTo("/checkout/pagamento");

        post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), null, 200);

        String again = post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), null, 409);
        assertThat(JsonPath.<String>read(again, "$.errorCode")).isEqualTo("CART_ALREADY_PAID");
    }

    @Test
    void pixPaymentReturnsASimulatedPixCode() {
        String cartId = newCart("familia");

        String body = post("/operations/checkout", "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"Pix\"}}",
                null, 200);

        Map<String, Object> payment = JsonPath.read(body, "$.payment");
        assertThat(payment).containsEntry("method", "Pix").containsEntry("lastDigits", null)
                .containsEntry("amount", 59.90);
        assertThat((String) payment.get("pixCode")).matches("E99999999\\d{12}[A-Z0-9]{11}");
    }

    @Test
    void unknownProductIsABusinessErrorWithNoCart() {
        String body = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"inexistente\"}]}", null, 422);

        assertThat(JsonPath.<String>read(body, "$.errorCode")).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(JsonPath.<Object>read(body, "$.cartId")).isNull();
    }

    @Test
    void unknownCartAndMethodNotOfferedAreRejected() {
        String unknownCart = post("/operations/checkout", cardPayment(UUID.randomUUID().toString(), APPROVED_CARD),
                null, 404);
        assertThat(JsonPath.<String>read(unknownCart, "$.errorCode")).isEqualTo("CART_NOT_FOUND");

        String cartId = newCart("basico");
        String boleto = post("/operations/checkout",
                "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"Boleto\"}}", null, 422);
        assertThat(JsonPath.<String>read(boleto, "$.errorCode")).isEqualTo("PAYMENT_METHOD_NOT_ALLOWED");
    }

    @Test
    void doubleClickChargesOnce() {
        String cartId = newCart("premium");
        WebClient client = WebClient.create("http://localhost:" + port);
        Mono<Integer> pay = client.post().uri("/operations/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cardPayment(cartId, APPROVED_CARD))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()));

        List<Integer> statuses = Flux.merge(pay, pay).collectList().block(Duration.ofSeconds(20));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(rawCart(cartId).getList("attempts", Document.class)).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyReplaysTheOutcomeWithoutChargingAgain() {
        String cartId = newCart("premium");
        String key = "order-" + UUID.randomUUID();

        String first = post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), key, 200);
        String replay = post("/operations/checkout", cardPayment(cartId, APPROVED_CARD), key, 200);

        assertThat(replay).isEqualTo(first);
        assertThat(rawCart(cartId).getList("attempts", Document.class)).hasSize(1);

        String otherCard = post("/operations/checkout", cardPayment(cartId, "5555555555554444"), key, 422);
        assertThat(JsonPath.<String>read(otherCard, "$.errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void catalogsAreServedFromRedisAfterTheFirstCall() {
        redis.execute(connection -> connection.serverCommands().flushAll()).blockLast(Duration.ofSeconds(5));
        PRODUCTS_SERVICE.resetRequests();
        PAYMENT_METHODS_SERVICE.resetRequests();

        newCart("familia");
        newCart("familia");

        PRODUCTS_SERVICE.verify(1, getRequestedFor(urlEqualTo("/products/familia")));
        PAYMENT_METHODS_SERVICE.verify(1, getRequestedFor(urlEqualTo("/payment-methods")));
    }

    @Test
    void productsServiceOutageIsATechnicalErrorAfterRetries() {
        StubMapping outage = PRODUCTS_SERVICE.stubFor(get("/products/instavel").atPriority(1).willReturn(serverError()));
        try {
            String body = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"instavel\"}]}", null, 503);

            assertThat(JsonPath.<String>read(body, "$.errorCode")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(JsonPath.<String>read(body, "$.frontendError.primaryButton.url")).isEqualTo("RETRY");
            PRODUCTS_SERVICE.verify(3, getRequestedFor(urlEqualTo("/products/instavel")));
        } finally {
            PRODUCTS_SERVICE.removeStub(outage);
        }
    }

    private String newCart(String slug) {
        String body = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"" + slug + "\"}]}", null, 200);
        return JsonPath.read(body, "$.cartId");
    }

    private String post(String uri, String json, String idempotencyKey, int expectedStatus) {
        return http.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .bodyValue(json)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private Document rawCart(String cartId) {
        return mongo.findById(cartId, Document.class, "carts").block(Duration.ofSeconds(5));
    }

    private static String cardPayment(String cartId, String number) {
        return """
                {"cartId":"%s","paymentMethod":{"name":"%s","card":{"holderName":"Ana Souza","number":"%s",\
                "expiry":"12/30","cvv":"123"}}}""".formatted(cartId, CREDIT_CARD, number);
    }
}
