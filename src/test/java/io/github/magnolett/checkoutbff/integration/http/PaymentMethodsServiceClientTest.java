package io.github.magnolett.checkoutbff.integration.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethod;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodsServiceClientTest {

    private static final WireMockServer server = new WireMockServer(options().dynamicPort());

    private PaymentMethodsServiceClient client;

    @BeforeAll
    static void start() {
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("methods", CircuitBreakerConfig.custom()
                .recordException(new TransientFailure()).build());
        Retry retry = Retry.of("methods", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryOnException(new TransientFailure()).build());
        client = new PaymentMethodsServiceClient(WebClient.create(server.baseUrl()), Duration.ofMillis(500),
                circuitBreaker, retry);
    }

    @Test
    void keepsOnlyEnabledMethodsTheBffKnowsHowToCharge() {
        server.stubFor(get("/payment-methods").willReturn(okJson("""
                {"paymentMethods":[
                  {"code":"CREDIT_CARD","displayName":"Cartão de Crédito","enabled":true},
                  {"code":"DEBIT_CARD","displayName":"Cartão de Débito","enabled":false},
                  {"code":"PIX","displayName":"Pix","enabled":true,"provider":"simulated-psp"},
                  {"code":"CRYPTO","displayName":"Cripto","enabled":true},
                  {"code":"BOLETO","displayName":"Boleto"}
                ]}""")));

        StepVerifier.create(client.findAvailable())
                .expectNext(new PaymentMethod(PaymentMethodType.CREDIT_CARD, "Cartão de Crédito"))
                .expectNext(new PaymentMethod(PaymentMethodType.PIX, "Pix"))
                .verifyComplete();
    }

    @Test
    void unavailableServiceIsATechnicalFailureAfterRetries() {
        server.stubFor(get("/payment-methods").willReturn(aResponse().withStatus(503)));

        StepVerifier.create(client.findAvailable())
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("payment-methods-service"))
                .verify();

        server.verify(3, getRequestedFor(urlEqualTo("/payment-methods")));
    }

    @Test
    void payloadWithoutTheListIsRejected() {
        server.stubFor(get("/payment-methods").willReturn(okJson("{\"methods\":[]}")));

        StepVerifier.create(client.findAvailable()).expectError(ServiceUnavailableException.class).verify();
    }
}
