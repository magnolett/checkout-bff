package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.AttemptStatus;
import io.github.magnolett.checkoutbff.checkout.domain.CardData;
import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.CartStatus;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutError;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAttempt;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.checkout.port.ConcurrentCartUpdateException;
import io.github.magnolett.checkoutbff.checkout.port.PaymentGateway;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckoutServiceTest {

    private static final Duration PAYMENT_TIMEOUT = Duration.ofSeconds(10);

    private InMemoryCartRepository carts;
    private PaymentGateway gateway;
    private TestClock clock;
    private CheckoutService service;
    private Cart cart;

    @BeforeEach
    void setUp() {
        carts = new InMemoryCartRepository();
        gateway = mock(PaymentGateway.class);
        // sub-second part on purpose: the service must truncate to seconds
        clock = new TestClock(NOW.plusMillis(789));
        service = new CheckoutService(carts, gateway, clock, new CheckoutProperties(TTL, PAYMENT_TIMEOUT, LEASE));
        cart = openCart();
        carts.seed(cart);
    }

    private PayCommand command(String method, CardData card, String key) {
        return new PayCommand(cart.id(), method, card, key);
    }

    private PayCommand credit(String key) {
        return command(CREDIT, card(), key);
    }

    private static Mono<PaymentAuthorization> approved(String transactionId) {
        return Mono.just(new PaymentAuthorization.Approved(transactionId));
    }

    private static Mono<PaymentAuthorization> declined(String reason) {
        return Mono.just(new PaymentAuthorization.Declined(reason));
    }

    private void gatewayApproves(String transactionId) {
        when(gateway.authorize(any())).thenReturn(approved(transactionId));
    }

    private PaymentReceipt pay(PayCommand command) {
        return service.pay(command).block(Duration.ofSeconds(5));
    }

    private void assertFailsWith(PayCommand command, CheckoutError expected) {
        StepVerifier.create(service.pay(command))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(CheckoutException.class, e -> {
                    assertThat(e.error()).isEqualTo(expected);
                    assertThat(e.cartId()).isEqualTo(command.cartId());
                }))
                .verify(Duration.ofSeconds(5));
    }

    private PendingPayment lastGatewayCall() {
        ArgumentCaptor<PendingPayment> captor = ArgumentCaptor.forClass(PendingPayment.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).authorize(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private Cart stored() {
        return carts.stored(cart.id());
    }

    /** Puts the stored cart in the middle of a payment whose lease ends 30s after NOW. */
    private UUID seedPendingAttempt(PayCommand command) {
        Cart inFlight = carts.stored(cart.id());
        PendingPayment pending = inFlight.startPayment(command.order(), NOW, LEASE);
        carts.seed(inFlight);
        return pending.attemptId();
    }

    // ---- cart lookup

    @Test
    void cartNotFound() {
        PayCommand unknown = new PayCommand(UUID.randomUUID(), CREDIT, card(), null);

        assertFailsWith(unknown, CheckoutError.CART_NOT_FOUND);
        verifyNoInteractions(gateway);
    }

    // ---- approval

    @Test
    void cardApprovalReturnsTheReceiptForTheSuccessScreen() {
        gatewayApproves("AUTH-1");

        PaymentReceipt receipt = pay(credit(null));

        assertThat(receipt.cartId()).isEqualTo(cart.id());
        assertThat(receipt.paidAt()).isEqualTo(NOW);
        assertThat(receipt.methodType()).isEqualTo(PaymentMethodType.CREDIT_CARD);
        assertThat(receipt.methodName()).isEqualTo(CREDIT);
        assertThat(receipt.lastDigits()).isEqualTo("4242");
        assertThat(receipt.pixCode()).isNull();
        assertThat(receipt.amount()).isEqualTo(Money.of("39.90", "BRL"));
        assertThat(receipt.products()).containsExactly(premium());
    }

    @Test
    void approvalPersistsPaidCartAndApprovedAttempt() {
        gatewayApproves("AUTH-1");

        pay(credit("key-1"));

        Cart saved = stored();
        assertThat(saved.status()).isEqualTo(CartStatus.PAID);
        assertThat(saved.paidAt()).isEqualTo(NOW);
        assertThat(saved.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(AttemptStatus.APPROVED);
            assertThat(attempt.transactionId()).isEqualTo("AUTH-1");
            assertThat(attempt.idempotencyKey()).isEqualTo("key-1");
        });
        assertThat(carts.persistedEvents()).singleElement().isInstanceOf(CheckoutEvent.PaymentApproved.class);
    }

    @Test
    void theProcessingStateIsPersistedBeforeTheGatewayIsCalled() {
        int[] updatesWhenGatewayWasCalled = new int[1];
        CartStatus[] statusWhenGatewayWasCalled = new CartStatus[1];
        when(gateway.authorize(any())).thenAnswer(inv -> {
            updatesWhenGatewayWasCalled[0] = carts.updateCalls();
            statusWhenGatewayWasCalled[0] = stored().status();
            return approved("AUTH-1");
        });

        pay(credit(null));

        assertThat(updatesWhenGatewayWasCalled[0]).isEqualTo(1);
        assertThat(statusWhenGatewayWasCalled[0]).isEqualTo(CartStatus.PROCESSING);
        assertThat(carts.updateCalls()).isEqualTo(2);
    }

    @Test
    void theGatewayReceivesTheCartAmountTheAttemptIdAndTheCard() {
        gatewayApproves("AUTH-1");

        pay(credit(null));

        PendingPayment sent = lastGatewayCall();
        assertThat(sent.cartId()).isEqualTo(cart.id());
        assertThat(sent.amount()).isEqualTo(Money.of("39.90", "BRL"));
        assertThat(sent.methodType()).isEqualTo(PaymentMethodType.CREDIT_CARD);
        assertThat(sent.card()).isEqualTo(card());
        assertThat(sent.attemptId()).isEqualTo(stored().attempts().get(0).id());
    }

    @Test
    void theFullCardNumberAndTheCvvAreNeverPersisted() {
        gatewayApproves("AUTH-1");

        pay(credit(null));

        for (Cart snapshot : carts.updates()) {
            assertThat(snapshot.attempts().toString()).doesNotContain(CARD_NUMBER).doesNotContain("411111111111");
        }
        assertThat(stored().attempts().get(0).lastDigits()).isEqualTo("4242");
    }

    @Test
    void pixApprovalUsesTheTransactionIdAsPixCodeAndHasNoLastDigits() {
        gatewayApproves("E99999999202610141600ABCDEFGHIJK");

        PaymentReceipt receipt = pay(command(PIX, null, null));

        assertThat(receipt.methodType()).isEqualTo(PaymentMethodType.PIX);
        assertThat(receipt.methodName()).isEqualTo(PIX);
        assertThat(receipt.pixCode()).isEqualTo("E99999999202610141600ABCDEFGHIJK");
        assertThat(receipt.lastDigits()).isNull();
    }

    @Test
    void cardReceiptNeverCarriesAPixCode() {
        gatewayApproves("AUTH-1");

        assertThat(pay(credit(null)).pixCode()).isNull();
    }

    @Test
    void timesAreTruncatedToSeconds() {
        gatewayApproves("AUTH-1");

        pay(credit(null));

        Cart saved = stored();
        assertThat(saved.paidAt()).isEqualTo(NOW);
        assertThat(saved.attempts().get(0).startedAt()).isEqualTo(NOW);
        assertThat(saved.attempts().get(0).completedAt()).isEqualTo(NOW);
        assertThat(saved.processingLeaseUntil()).isNull();
    }

    // ---- decline

    @Test
    void declinePersistsDeclinedAndFailsWithPaymentDeclined() {
        when(gateway.authorize(any())).thenReturn(declined("insufficient funds"));

        assertFailsWith(credit(null), CheckoutError.PAYMENT_DECLINED);

        Cart saved = stored();
        assertThat(saved.status()).isEqualTo(CartStatus.DECLINED);
        assertThat(saved.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(AttemptStatus.DECLINED);
            assertThat(attempt.declineReason()).isEqualTo("insufficient funds");
        });
        assertThat(carts.persistedEvents()).singleElement().isInstanceOf(CheckoutEvent.PaymentDeclined.class);
        assertThat(saved.approvedAttempt()).isEmpty();
    }

    @Test
    void aDeclinedCartCanBePaidWithAnotherInstrument() {
        when(gateway.authorize(any())).thenReturn(declined("insufficient funds"), approved("E999"));

        assertFailsWith(credit(null), CheckoutError.PAYMENT_DECLINED);
        PaymentReceipt receipt = pay(command(PIX, null, null));

        assertThat(receipt.pixCode()).isEqualTo("E999");
        assertThat(stored().status()).isEqualTo(CartStatus.PAID);
        assertThat(stored().attempts()).extracting(PaymentAttempt::status)
                .containsExactly(AttemptStatus.DECLINED, AttemptStatus.APPROVED);
    }

    // ---- rules of the cart, as seen through the service

    @Test
    void aPaidCartCannotBePaidAgain() {
        gatewayApproves("AUTH-1");
        pay(credit(null));

        assertFailsWith(credit(null), CheckoutError.CART_ALREADY_PAID);
        assertFailsWith(command(PIX, null, null), CheckoutError.CART_ALREADY_PAID);

        verify(gateway, times(1)).authorize(any());
    }

    @Test
    void methodNotOfferedNeverReachesTheGateway() {
        assertFailsWith(command("Boleto", null, null), CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);

        verifyNoInteractions(gateway);
        assertThat(carts.updateCalls()).isZero();
    }

    @Test
    void invalidCardNeverReachesTheGateway() {
        PayCommand expired = command(CREDIT, new CardData("Ana Souza", CARD_NUMBER, "01/20", CVV), null);

        assertFailsWith(expired, CheckoutError.INVALID_PAYMENT_DATA);

        verifyNoInteractions(gateway);
        assertThat(carts.updateCalls()).isZero();
    }

    @Test
    void pixWithCardDataNeverReachesTheGateway() {
        assertFailsWith(command(PIX, card(), null), CheckoutError.INVALID_PAYMENT_DATA);

        verifyNoInteractions(gateway);
    }

    @Test
    void expiredCartNeverReachesTheGateway() {
        clock.set(NOW.plus(TTL).plusSeconds(1));

        assertFailsWith(credit(null), CheckoutError.CART_EXPIRED);

        verifyNoInteractions(gateway);
    }

    @Test
    void aPaymentRunningOnTheCartBlocksAnotherOneWithoutCallingTheGateway() {
        seedPendingAttempt(credit("key-1"));

        assertFailsWith(credit("key-2"), CheckoutError.PAYMENT_IN_PROGRESS);
        assertFailsWith(command(PIX, null, null), CheckoutError.PAYMENT_IN_PROGRESS);

        verifyNoInteractions(gateway);
    }

    // ---- optimistic lock

    @Test
    void losingTheOptimisticLockOnTheFirstSaveIsPaymentInProgressAndTheGatewayIsNeverCalled() {
        carts.failUpdate(1, new ConcurrentCartUpdateException(cart.id()));

        assertFailsWith(credit(null), CheckoutError.PAYMENT_IN_PROGRESS);

        verifyNoInteractions(gateway);
        assertThat(stored().status()).isEqualTo(CartStatus.OPEN);
        assertThat(stored().attempts()).isEmpty();
    }

    // ---- idempotency

    @Test
    void sameKeyAfterApprovalReplaysTheReceiptWithoutCallingTheGatewayAgain() {
        gatewayApproves("AUTH-1");
        PaymentReceipt first = pay(credit("key-1"));

        PaymentReceipt replay = pay(credit("key-1"));

        assertThat(replay).isEqualTo(first);
        verify(gateway, times(1)).authorize(any());
        assertThat(carts.updateCalls()).isEqualTo(2);
    }

    @Test
    void sameKeyAfterDeclineReplaysPaymentDeclinedWithoutCallingTheGatewayAgain() {
        when(gateway.authorize(any())).thenReturn(declined("insufficient funds"));
        assertFailsWith(credit("key-1"), CheckoutError.PAYMENT_DECLINED);

        assertFailsWith(credit("key-1"), CheckoutError.PAYMENT_DECLINED);

        verify(gateway, times(1)).authorize(any());
        assertThat(stored().attempts()).hasSize(1);
    }

    @Test
    void sameKeyWhileTheAttemptIsStillRunningIsPaymentInProgress() {
        seedPendingAttempt(credit("key-1"));

        assertFailsWith(credit("key-1"), CheckoutError.PAYMENT_IN_PROGRESS);

        verifyNoInteractions(gateway);
    }

    @Test
    void sameKeyAfterTheLeaseExpiredResumesTheSameAttemptId() {
        UUID attemptId = seedPendingAttempt(credit("key-1"));
        clock.set(NOW.plus(LEASE).plusSeconds(5));
        gatewayApproves("AUTH-1");

        PaymentReceipt receipt = pay(credit("key-1"));

        assertThat(lastGatewayCall().attemptId()).isEqualTo(attemptId);
        assertThat(receipt.cartId()).isEqualTo(cart.id());
        assertThat(stored().attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.id()).isEqualTo(attemptId);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.APPROVED);
        });
    }

    @Test
    void sameKeyWithADifferentPaymentIsIdempotencyKeyReusedAfterApproval() {
        gatewayApproves("AUTH-1");
        pay(credit("key-1"));

        assertFailsWith(command(PIX, null, "key-1"), CheckoutError.IDEMPOTENCY_KEY_REUSED);
        assertFailsWith(command(CREDIT, cardEndingIn("9999"), "key-1"), CheckoutError.IDEMPOTENCY_KEY_REUSED);

        verify(gateway, times(1)).authorize(any());
    }

    @Test
    void sameKeyWithADifferentPaymentIsIdempotencyKeyReusedAfterDecline() {
        when(gateway.authorize(any())).thenReturn(declined("insufficient funds"));
        assertFailsWith(credit("key-1"), CheckoutError.PAYMENT_DECLINED);

        assertFailsWith(command(PIX, null, "key-1"), CheckoutError.IDEMPOTENCY_KEY_REUSED);

        verify(gateway, times(1)).authorize(any());
    }

    @Test
    void sameKeyWithADifferentPaymentIsIdempotencyKeyReusedWhileRunning() {
        seedPendingAttempt(credit("key-1"));

        assertFailsWith(command(PIX, null, "key-1"), CheckoutError.IDEMPOTENCY_KEY_REUSED);

        verifyNoInteractions(gateway);
    }

    @Test
    void aKeyFromAnotherCartIsNotAReplay() {
        gatewayApproves("AUTH-1");
        pay(credit("key-1"));
        Cart other = openCart();
        carts.seed(other);

        PaymentReceipt receipt = service.pay(new PayCommand(other.id(), CREDIT, card(), "key-1")).block();

        assertThat(receipt.cartId()).isEqualTo(other.id());
        verify(gateway, times(2)).authorize(any());
    }

    @Test
    void anotherKeyAfterApprovalIsStillCartAlreadyPaid() {
        gatewayApproves("AUTH-1");
        pay(credit("key-1"));

        assertFailsWith(credit("key-2"), CheckoutError.CART_ALREADY_PAID);
    }

    // ---- gateway without an answer

    @Test
    void gatewayTimeoutIsServiceUnavailableAfterThePaymentTimeout() {
        when(gateway.authorize(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> service.pay(credit(null)))
                .expectSubscription()
                .expectNoEvent(PAYMENT_TIMEOUT.minusMillis(100))
                .thenAwait(Duration.ofMillis(200))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void gatewayTimeoutKeepsTheAttemptPendingAndReleasesTheLeaseAtOnce() {
        when(gateway.authorize(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> service.pay(credit(null)))
                .thenAwait(PAYMENT_TIMEOUT.plusSeconds(1))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));

        Cart saved = stored();
        assertThat(saved.attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.status()).isEqualTo(AttemptStatus.PENDING));
        assertThat(saved.isProcessing(NOW.plusMillis(789))).isFalse();
        assertThat(saved.status()).isNotEqualTo(CartStatus.PAID);
        assertThat(carts.persistedEvents()).isEmpty();
        assertThat(carts.updateCalls()).isEqualTo(2);
    }

    @Test
    void aRetryAfterATimeoutResumesTheSameAttemptId() {
        when(gateway.authorize(any())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.pay(credit(null)))
                .thenAwait(PAYMENT_TIMEOUT.plusSeconds(1))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        UUID firstAttempt = stored().attempts().get(0).id();
        gatewayApproves("AUTH-1");

        PaymentReceipt receipt = pay(credit(null));

        assertThat(receipt.lastDigits()).isEqualTo("4242");
        assertThat(lastGatewayCall().attemptId()).isEqualTo(firstAttempt);
        assertThat(stored().attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.id()).isEqualTo(firstAttempt);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.APPROVED);
        });
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyAfterATimeoutAlsoResumes() {
        when(gateway.authorize(any())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.pay(credit("key-1")))
                .thenAwait(PAYMENT_TIMEOUT.plusSeconds(1))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        UUID firstAttempt = stored().attempts().get(0).id();
        gatewayApproves("AUTH-1");

        pay(credit("key-1"));

        assertThat(lastGatewayCall().attemptId()).isEqualTo(firstAttempt);
        assertThat(stored().status()).isEqualTo(CartStatus.PAID);
    }

    @Test
    void aRetryWithAnotherInstrumentAfterATimeoutAbandonsTheUnknownAttempt() {
        when(gateway.authorize(any())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.pay(credit(null)))
                .thenAwait(PAYMENT_TIMEOUT.plusSeconds(1))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        UUID firstAttempt = stored().attempts().get(0).id();
        gatewayApproves("E999");

        pay(command(PIX, null, null));

        assertThat(stored().attempts()).hasSize(2);
        assertThat(stored().attempts().get(0).id()).isEqualTo(firstAttempt);
        assertThat(stored().attempts().get(0).status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(stored().attempts().get(1).status()).isEqualTo(AttemptStatus.APPROVED);
    }

    @Test
    void aGatewayErrorIsServiceUnavailableAndKeepsTheAttemptPending() {
        when(gateway.authorize(any())).thenReturn(Mono.error(new IllegalStateException("connection reset")));

        StepVerifier.create(service.pay(credit(null)))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));

        Cart saved = stored();
        assertThat(saved.attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.status()).isEqualTo(AttemptStatus.PENDING));
        assertThat(saved.isProcessing(NOW.plusMillis(789))).isFalse();
    }

    @Test
    void aGatewayThatFailsWithServiceUnavailableIsPassedThroughAndTheLeaseIsReleased() {
        ServiceUnavailableException failure = new ServiceUnavailableException("payment-gateway", "503");
        when(gateway.authorize(any())).thenReturn(Mono.error(failure));

        StepVerifier.create(service.pay(credit(null)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify(Duration.ofSeconds(5));

        assertThat(stored().isProcessing(NOW.plusMillis(789))).isFalse();
        assertThat(stored().attempts().get(0).status()).isEqualTo(AttemptStatus.PENDING);
    }

    @Test
    void aRetryAfterAGatewayErrorResumesTheSameAttemptId() {
        when(gateway.authorize(any())).thenReturn(Mono.error(new ServiceUnavailableException("payment-gateway", "down")));
        StepVerifier.create(service.pay(credit(null))).expectError().verify(Duration.ofSeconds(5));
        UUID firstAttempt = stored().attempts().get(0).id();
        gatewayApproves("AUTH-1");

        pay(credit(null));

        assertThat(lastGatewayCall().attemptId()).isEqualTo(firstAttempt);
    }

    @Test
    void ifReleasingTheLeaseFailsTheOriginalFailureStillReachesTheCaller() {
        when(gateway.authorize(any())).thenReturn(Mono.error(new ServiceUnavailableException("payment-gateway", "down")));
        carts.failUpdate(2, new IllegalStateException("mongo down"));

        StepVerifier.create(service.pay(credit(null)))
                .expectError(ServiceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void aTimeoutIsNotReportedAsADecline() {
        when(gateway.authorize(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> service.pay(credit(null)))
                .thenAwait(PAYMENT_TIMEOUT.plusSeconds(1))
                .expectErrorSatisfies(error -> assertThat(error).isNotInstanceOf(CheckoutException.class))
                .verify(Duration.ofSeconds(5));
        assertThat(stored().status()).isNotEqualTo(CartStatus.DECLINED);
    }
}
