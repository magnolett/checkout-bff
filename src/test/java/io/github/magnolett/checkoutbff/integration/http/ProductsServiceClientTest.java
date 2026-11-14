package io.github.magnolett.checkoutbff.integration.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
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
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

class ProductsServiceClientTest {

    private static final String PREMIUM = """
            {"id":"prod-0002","slug":"premium","title":"Plano Premium",\
            "summary":"Assinatura mensal com catálogo completo",\
            "pricing":{"amount":39.90,"currency":"BRL","billingPeriod":"MONTHLY"},"metadata":{"sku":"SUB-PREMIUM-M"}}""";

    private static final WireMockServer server = new WireMockServer(options().dynamicPort());

    private CircuitBreaker circuitBreaker;
    private ProductsServiceClient client;

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
        circuitBreaker = CircuitBreaker.of("products", CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordException(new TransientFailure())
                .build());
        Retry retry = Retry.of("products", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(new TransientFailure())
                .build());
        client = new ProductsServiceClient(WebClient.create(server.baseUrl()), Duration.ofMillis(500), circuitBreaker,
                retry);
    }

    @Test
    void translatesTheServiceModelToTheDomain() {
        server.stubFor(get("/products/premium").willReturn(okJson(PREMIUM)));

        StepVerifier.create(client.findBySlug("premium"))
                .assertNext(product -> {
                    assertThat(product.slug()).isEqualTo("premium");
                    assertThat(product.name()).isEqualTo("Plano Premium");
                    assertThat(product.description()).isEqualTo("Assinatura mensal com catálogo completo");
                    assertThat(product.price()).isEqualTo(Money.of("39.90", "BRL"));
                })
                .verifyComplete();
    }

    @Test
    void notFoundIsAnAnswerNotAFailure() {
        server.stubFor(get("/products/nope").willReturn(notFound()));

        StepVerifier.create(client.findBySlug("nope")).verifyComplete();

        server.verify(1, getRequestedFor(urlEqualTo("/products/nope")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void transientErrorIsRetriedUntilItSucceeds() {
        server.stubFor(get("/products/premium").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(serverError()).willSetStateTo("second"));
        server.stubFor(get("/products/premium").inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("third"));
        server.stubFor(get("/products/premium").inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(okJson(PREMIUM)));

        StepVerifier.create(client.findBySlug("premium")).expectNextCount(1).verifyComplete();

        server.verify(3, getRequestedFor(urlEqualTo("/products/premium")));
    }

    @Test
    void persistentServerErrorBecomesServiceUnavailableAfterThreeAttempts() {
        server.stubFor(get(urlPathMatching("/products/.*")).willReturn(serverError()));

        StepVerifier.create(client.findBySlug("premium"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("HTTP 500"))
                .verify();

        server.verify(3, getRequestedFor(urlEqualTo("/products/premium")));
    }

    @Test
    void clientErrorIsNeitherRetriedNorCountedAgainstTheService() {
        server.stubFor(get("/products/premium").willReturn(badRequest()));

        StepVerifier.create(client.findBySlug("premium")).expectError(ServiceUnavailableException.class).verify();

        server.verify(1, getRequestedFor(urlEqualTo("/products/premium")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void slowAnswerTimesOutAndIsRetried() {
        server.stubFor(get("/products/premium").willReturn(okJson(PREMIUM).withFixedDelay(1_500)));

        StepVerifier.create(client.findBySlug("premium"))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("no answer in time"))
                .verify(Duration.ofSeconds(10));

        server.verify(3, getRequestedFor(urlEqualTo("/products/premium")));
    }

    @Test
    void openCircuitStopsCallingTheService() {
        server.stubFor(get(urlPathMatching("/products/.*")).willReturn(serverError()));
        StepVerifier.create(client.findBySlug("premium")).expectError().verify();
        StepVerifier.create(client.findBySlug("premium")).expectError().verify();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        server.resetRequests();

        StepVerifier.create(client.findBySlug("premium"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("circuit breaker open"))
                .verify();

        server.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void incompletePayloadIsRejected() {
        server.stubFor(get("/products/premium").willReturn(okJson("{\"slug\":\"premium\",\"title\":\"Plano Premium\"}")));

        StepVerifier.create(client.findBySlug("premium"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("invalid answer"))
                .verify();
    }
}
