package io.github.magnolett.checkoutbff.integration.gateway;

import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.integration.IntegrationProperties;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T16:00:00Z"), ZoneOffset.UTC);
    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(properties(), clock);

    @Test
    void cardEndingIn0002IsDeclined() {
        PaymentAuthorization answer = gateway.authorize(card("4000000000000002")).block();

        assertThat(answer).isInstanceOf(PaymentAuthorization.Declined.class);
    }

    @Test
    void anyOtherCardIsApproved() {
        PendingPayment payment = card("4242424242424242");

        PaymentAuthorization answer = gateway.authorize(payment).block();

        assertThat(answer).isEqualTo(new PaymentAuthorization.Approved("SIM-" + payment.attemptId()));
    }

    @Test
    void pixGetsAnEndToEndIdInTheCentralBankFormat() {
        PaymentAuthorization answer = gateway.authorize(pix()).block();

        assertThat(answer).isInstanceOfSatisfying(PaymentAuthorization.Approved.class, approved ->
                assertThat(approved.transactionId())
                        .hasSize(32)
                        .matches("E99999999202610141600[A-Z0-9]{11}"));
    }

    @Test
    void sameAttemptGetsTheSameAnswerLikeARealProvider() {
        PendingPayment payment = pix();

        PaymentAuthorization first = gateway.authorize(payment).block();
        PaymentAuthorization second = gateway.authorize(payment).block();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theStatusQueryReturnsTheOutcomeRecordedForTheAttempt() {
        PendingPayment payment = card("4000000000000002");
        PaymentAuthorization answered = gateway.authorize(payment).block();

        assertThat(gateway.outcomeOf(payment.attemptId()).block()).isEqualTo(answered);
    }

    @Test
    void theStatusQueryIsEmptyForAnAttemptTheProviderNeverReceived() {
        assertThat(gateway.outcomeOf(UUID.randomUUID()).blockOptional()).isEmpty();
    }

    @Test
    void aCallerThatGivesUpBeforeTheAnswerLeavesTheChargeRecordedAtTheProvider() {
        SimulatedPaymentGateway slow = new SimulatedPaymentGateway(properties(Duration.ofSeconds(5)), clock);
        PendingPayment payment = card("4242424242424242");

        StepVerifier.create(slow.authorize(payment).timeout(Duration.ofMillis(50)))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(2));

        assertThat(slow.outcomeOf(payment.attemptId()).block())
                .isEqualTo(new PaymentAuthorization.Approved("SIM-" + payment.attemptId()));
    }

    private static PendingPayment card(String number) {
        return new PendingPayment(UUID.randomUUID(), UUID.randomUUID(), PaymentMethodType.CREDIT_CARD,
                Money.of("39.90", "BRL"), new CardData("Ana Souza", number, "12/30", "123"));
    }

    private static PendingPayment pix() {
        return new PendingPayment(UUID.randomUUID(), UUID.randomUUID(), PaymentMethodType.PIX,
                Money.of("39.90", "BRL"), null);
    }

    private static IntegrationProperties properties() {
        return properties(Duration.ZERO);
    }

    private static IntegrationProperties properties(Duration latency) {
        IntegrationProperties.Endpoint endpoint = new IntegrationProperties.Endpoint(URI.create("http://localhost"),
                Duration.ofSeconds(1), Duration.ofMinutes(5));
        return new IntegrationProperties(endpoint, endpoint,
                new IntegrationProperties.Gateway("99999999", latency),
                new IntegrationProperties.Persistence(Duration.ofHours(24)));
    }
}
