package io.github.magnolett.checkoutbff;

import com.jayway.jsonpath.JsonPath;
import io.github.magnolett.checkoutbff.support.IntegrationTestBase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The gateway answer gets lost on the way back: the simulated provider decides as soon as the request arrives, but
 * answers after the checkout has given up. With real infrastructure, coming back with a different instrument must find
 * the first charge, settle the cart with it and charge nothing else.
 */
@AutoConfigureWebTestClient(timeout = "20s")
@DirtiesContext
@TestPropertySource(properties = {
        "messaging.outbox.relay-interval=200ms",
        "checkout.payment-timeout=300ms",
        "integration.payment-gateway.latency=2s"})
class LostAnswerIT extends IntegrationTestBase {

    private static final String APPROVED_CARD = "4242424242424242";

    @Autowired
    private WebTestClient http;

    @Autowired
    private ReactiveMongoTemplate mongo;

    @Test
    void anotherInstrumentAfterALostAnswerFindsTheFirstChargeAndChargesNothingElse() {
        String cartId = newCart();

        String lost = post(cardPayment(cartId), 503);
        assertThat(JsonPath.<String>read(lost, "$.errorCode")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(JsonPath.<String>read(lost, "$.cartId")).isEqualTo(cartId);

        String pix = post("{\"cartId\":\"%s\",\"paymentMethod\":{\"name\":\"Pix\"}}".formatted(cartId), 409);
        assertThat(JsonPath.<String>read(pix, "$.errorCode")).isEqualTo("CART_ALREADY_PAID");

        Document cart = mongo.findById(cartId, Document.class, "carts").block(Duration.ofSeconds(5));
        assertThat(cart).isNotNull();
        assertThat(cart.getString("status")).isEqualTo("PAID");
        List<Document> attempts = cart.getList("attempts", Document.class);
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getString("status")).isEqualTo("APPROVED");
            assertThat(attempt.getString("lastDigits")).isEqualTo("4242");
            assertThat(attempt.getString("transactionId")).startsWith("SIM-");
        });

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Document subscription = mongo.findById(cartId, Document.class, "subscriptions").block();
            assertThat(subscription).isNotNull();
            assertThat(subscription.getString("status")).isEqualTo("ACTIVE");
            assertThat(subscription.getString("transactionId")).startsWith("SIM-");
        });
    }

    private String newCart() {
        String body = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"premium\"}]}", 200);
        return JsonPath.read(body, "$.cartId");
    }

    private String post(String json, int expectedStatus) {
        return post("/operations/checkout", json, expectedStatus);
    }

    private String post(String uri, String json, int expectedStatus) {
        return http.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static String cardPayment(String cartId) {
        return """
                {"cartId":"%s","paymentMethod":{"name":"Cartão de Crédito","card":{"holderName":"Ana Souza",\
                "number":"%s","expiry":"12/30","cvv":"123"}}}""".formatted(cartId, APPROVED_CARD);
    }
}
