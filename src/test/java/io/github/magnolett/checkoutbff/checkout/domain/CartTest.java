package io.github.magnolett.checkoutbff.checkout.domain;

import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentApproved;
import io.github.magnolett.checkoutbff.checkout.domain.CheckoutEvent.PaymentDeclined;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.github.magnolett.checkoutbff.checkout.CheckoutFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    private static final Instant LATER = NOW.plusSeconds(5);

    private static void assertFails(Cart cart, PaymentOrder order, Instant now, CheckoutError expected) {
        assertThatThrownBy(() -> cart.startPayment(order, now, LEASE))
                .isInstanceOfSatisfying(CheckoutException.class, e -> {
                    assertThat(e.error()).isEqualTo(expected);
                    assertThat(e.cartId()).isEqualTo(cart.id());
                });
    }

    private static PaymentAttempt only(Cart cart) {
        assertThat(cart.attempts()).hasSize(1);
        return cart.attempts().get(0);
    }

    @Nested
    class Creation {

        @Test
        void startsOpenWithTheSumOfThePriceSnapshotAsTotal() {
            Cart cart = Cart.create(UUID.randomUUID(), List.of(premium(), basic()), allMethods(), NOW, TTL);

            assertThat(cart.status()).isEqualTo(CartStatus.OPEN);
            assertThat(cart.total()).isEqualTo(Money.of("59.80", "BRL"));
            assertThat(cart.version()).isZero();
            assertThat(cart.attempts()).isEmpty();
            assertThat(cart.paidAt()).isNull();
            assertThat(cart.processingLeaseUntil()).isNull();
            assertThat(cart.createdAt()).isEqualTo(NOW);
            assertThat(cart.expiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(cart.productSlugs()).containsExactly("premium", "basico");
        }

        @Test
        void needsAtLeastOneProductAndOnePaymentMethod() {
            assertThatThrownBy(() -> Cart.create(UUID.randomUUID(), List.of(), allMethods(), NOW, TTL))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Cart.create(UUID.randomUUID(), List.of(premium()), List.of(), NOW, TTL))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesProductsInDifferentCurrencies() {
            Product dollars = new Product("usd", "USD plan", null, Money.of("5.00", "USD"));

            assertThatThrownBy(() -> Cart.create(UUID.randomUUID(), List.of(premium(), dollars), allMethods(), NOW, TTL))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class StartingAPayment {

        @Test
        void movesToProcessingHoldsTheLeaseAndOpensAPendingAttempt() {
            Cart cart = openCart();

            PendingPayment pending = cart.startPayment(creditOrder("key-1"), NOW, LEASE);

            assertThat(cart.status()).isEqualTo(CartStatus.PROCESSING);
            assertThat(cart.processingLeaseUntil()).isEqualTo(NOW.plus(LEASE));
            assertThat(cart.isProcessing(NOW)).isTrue();
            assertThat(pending.cartId()).isEqualTo(cart.id());
            assertThat(pending.methodType()).isEqualTo(PaymentMethodType.CREDIT_CARD);
            assertThat(pending.amount()).isEqualTo(Money.of("39.90", "BRL"));
            assertThat(pending.card()).isEqualTo(card());
            PaymentAttempt attempt = only(cart);
            assertThat(attempt.id()).isEqualTo(pending.attemptId());
            assertThat(attempt.status()).isEqualTo(AttemptStatus.PENDING);
            assertThat(attempt.idempotencyKey()).isEqualTo("key-1");
            assertThat(attempt.fingerprint()).isEqualTo(creditOrder().fingerprint());
            assertThat(attempt.methodName()).isEqualTo(CREDIT);
            assertThat(attempt.methodType()).isEqualTo(PaymentMethodType.CREDIT_CARD);
            assertThat(attempt.lastDigits()).isEqualTo("4242");
            assertThat(attempt.amount()).isEqualTo(Money.of("39.90", "BRL"));
            assertThat(attempt.startedAt()).isEqualTo(NOW);
            assertThat(attempt.completedAt()).isNull();
        }

        @Test
        void neverStoresTheFullNumberOrTheCvvInTheAttempt() {
            Cart cart = openCart();

            cart.startPayment(creditOrder("key-1"), NOW, LEASE);

            assertThat(cart.attempts().toString())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("411111111111")
                    .doesNotContain("cvv");
            assertThat(only(cart).lastDigits()).hasSize(4);
        }

        @Test
        void pixNeedsNoCardAndLeavesLastDigitsNull() {
            Cart cart = openCart();

            PendingPayment pending = cart.startPayment(pixOrder(), NOW, LEASE);

            assertThat(pending.methodType()).isEqualTo(PaymentMethodType.PIX);
            assertThat(pending.card()).isNull();
            assertThat(only(cart).lastDigits()).isNull();
        }

        @Test
        void debitCardIsAcceptedLikeCredit() {
            Cart cart = openCart();

            PendingPayment pending = cart.startPayment(new PaymentOrder(DEBIT, card(), null), NOW, LEASE);

            assertThat(pending.methodType()).isEqualTo(PaymentMethodType.DEBIT_CARD);
        }

        @Test
        void cardMethodWithoutCardIsInvalidPaymentData() {
            Cart cart = openCart();

            assertFails(cart, new PaymentOrder(CREDIT, null, null), NOW, CheckoutError.INVALID_PAYMENT_DATA);
            assertFails(cart, new PaymentOrder(DEBIT, null, null), NOW, CheckoutError.INVALID_PAYMENT_DATA);
        }

        @Test
        void pixWithCardDataIsInvalidPaymentData() {
            assertFails(openCart(), new PaymentOrder(PIX, card(), null), NOW, CheckoutError.INVALID_PAYMENT_DATA);
        }

        @Test
        void invalidCardIsInvalidPaymentDataAndDoesNotTouchTheCart() {
            Cart cart = openCart();
            PaymentOrder expired = new PaymentOrder(CREDIT, new CardData("Ana Souza", CARD_NUMBER, "09/26", CVV), null);

            assertFails(cart, expired, NOW, CheckoutError.INVALID_PAYMENT_DATA);

            assertThat(cart.status()).isEqualTo(CartStatus.OPEN);
            assertThat(cart.attempts()).isEmpty();
            assertThat(cart.processingLeaseUntil()).isNull();
        }

        @Test
        void expiryIsComparedWithTheCurrentUtcMonth() {
            Cart cart = openCart();
            PaymentOrder thisMonth = new PaymentOrder(CREDIT, new CardData("Ana Souza", CARD_NUMBER, "10/26", CVV), null);
            Instant lastSecondOfOctoberUtc = Instant.parse("2026-10-31T23:59:59Z");
            Instant firstSecondOfNovemberUtc = Instant.parse("2026-11-01T00:00:00Z");
            Cart longLived = Cart.create(UUID.randomUUID(), List.of(premium()), allMethods(), NOW, Duration.ofDays(40));

            assertThat(cart.startPayment(thisMonth, NOW, LEASE)).isNotNull();
            assertThat(longLived.startPayment(thisMonth, lastSecondOfOctoberUtc, LEASE)).isNotNull();
            assertFails(Cart.create(UUID.randomUUID(), List.of(premium()), allMethods(), NOW, Duration.ofDays(40)),
                    thisMonth, firstSecondOfNovemberUtc, CheckoutError.INVALID_PAYMENT_DATA);
        }

        @Test
        void methodMustBeOneTheCartOfferedByExactName() {
            Cart cart = openCart();

            assertFails(cart, new PaymentOrder("Boleto", null, null), NOW, CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
            assertFails(cart, new PaymentOrder("pix", null, null), NOW, CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
            assertFails(cart, new PaymentOrder(" Pix ", null, null), NOW, CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
            assertFails(cart, new PaymentOrder(null, null, null), NOW, CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
            assertThat(cart.attempts()).isEmpty();
            assertThat(cart.status()).isEqualTo(CartStatus.OPEN);
        }

        @Test
        void aMethodTheCartDidNotOfferIsRefusedEvenIfTheBffKnowsTheType() {
            Cart cart = Cart.create(UUID.randomUUID(), List.of(premium()),
                    List.of(new PaymentMethod(PaymentMethodType.CREDIT_CARD, CREDIT)), NOW, TTL);

            assertFails(cart, pixOrder(), NOW, CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
        }

        @Test
        void errorsCarryNoCardData() {
            Cart cart = openCart();
            PaymentOrder bad = new PaymentOrder(CREDIT, new CardData("Ana Souza", CARD_NUMBER, "09/26", "987"), null);

            assertThatThrownBy(() -> cart.startPayment(bad, NOW, LEASE))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CARD_NUMBER).doesNotContain("987"));
        }
    }

    @Nested
    class CheckOrder {

        @Test
        void paidBeatsEverythingElse() {
            Cart cart = openCart();
            PendingPayment pending = cart.startPayment(creditOrder(), NOW, LEASE);
            cart.approve(pending.attemptId(), "AUTH-1", NOW);
            Instant afterExpiry = cart.expiresAt().plusSeconds(1);

            assertFails(cart, new PaymentOrder("Boleto", null, null), afterExpiry, CheckoutError.CART_ALREADY_PAID);
        }

        @Test
        void activeLeaseBeatsExpiryAndWrongMethod() {
            Cart cart = Cart.create(UUID.randomUUID(), List.of(premium()), allMethods(), NOW, Duration.ofSeconds(10));
            cart.startPayment(creditOrder(), NOW, LEASE);
            Instant afterExpiryInsideLease = NOW.plusSeconds(15);

            assertFails(cart, new PaymentOrder("Boleto", null, null), afterExpiryInsideLease,
                    CheckoutError.PAYMENT_IN_PROGRESS);
        }

        @Test
        void expiryBeatsWrongMethod() {
            Cart cart = openCart();
            Instant afterExpiry = cart.expiresAt().plusSeconds(1);

            assertFails(cart, new PaymentOrder("Boleto", null, null), afterExpiry, CheckoutError.CART_EXPIRED);
        }

        @Test
        void wrongMethodBeatsBadInstrument() {
            assertFails(openCart(), new PaymentOrder("Boleto", card(), null), NOW,
                    CheckoutError.PAYMENT_METHOD_NOT_ALLOWED);
        }

        @Test
        void aNewPaymentOnAnExpiredCartIsRefused() {
            Cart cart = openCart();

            assertFails(cart, creditOrder(), cart.expiresAt().plusSeconds(1), CheckoutError.CART_EXPIRED);
            assertThat(cart.attempts()).isEmpty();
        }

        @Test
        void aCartJustBeforeExpiryStillAcceptsPayment() {
            Cart cart = openCart();

            assertThat(cart.startPayment(creditOrder(), cart.expiresAt().minusSeconds(1), LEASE)).isNotNull();
        }
    }

    @Nested
    class LeaseAndResume {

        @Test
        void aSecondRequestInsideTheLeaseIsInProgress() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);

            assertFails(cart, creditOrder(), NOW.plusSeconds(10), CheckoutError.PAYMENT_IN_PROGRESS);
            assertFails(cart, pixOrder(), NOW.plusSeconds(10), CheckoutError.PAYMENT_IN_PROGRESS);
            assertThat(cart.attempts()).hasSize(1);
        }

        @Test
        void theLeaseEndsOnItsDeadline() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);

            assertThat(cart.isProcessing(NOW.plus(LEASE).minusSeconds(1))).isTrue();
            assertThat(cart.isProcessing(NOW.plus(LEASE))).isFalse();
        }

        @Test
        void sameInstrumentAfterTheLeaseResumesTheSameAttempt() {
            Cart cart = openCart();
            UUID first = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

            PendingPayment resumed = cart.startPayment(creditOrder(), afterLease, LEASE);

            assertThat(resumed.attemptId()).isEqualTo(first);
            assertThat(cart.attempts()).hasSize(1);
            assertThat(only(cart).status()).isEqualTo(AttemptStatus.PENDING);
            assertThat(cart.processingLeaseUntil()).isEqualTo(afterLease.plus(LEASE));
        }

        @Test
        void resumeIgnoresCvvAndHolderBecauseTheFingerprintIsMethodLastDigitsAndExpiry() {
            Cart cart = openCart();
            UUID first = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            PaymentOrder sameInstrument = new PaymentOrder(CREDIT,
                    new CardData("A. Souza", "5555 5555 5555 4242", "12/28", "999"), null);

            PendingPayment resumed = cart.startPayment(sameInstrument, NOW.plus(LEASE).plusSeconds(1), LEASE);

            assertThat(resumed.attemptId()).isEqualTo(first);
        }

        @Test
        void resumeStillWorksAfterTheCartExpired() {
            Cart cart = openCart();
            UUID first = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            Instant afterExpiry = cart.expiresAt().plusSeconds(60);

            PendingPayment resumed = cart.startPayment(creditOrder(), afterExpiry, LEASE);

            assertThat(resumed.attemptId()).isEqualTo(first);
        }

        @Test
        void aDifferentInstrumentAfterExpiryIsRefusedAndKeepsThePendingAttempt() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);
            Instant afterExpiry = cart.expiresAt().plusSeconds(60);

            assertFails(cart, new PaymentOrder(CREDIT, cardEndingIn("1111"), null), afterExpiry,
                    CheckoutError.CART_EXPIRED);

            assertThat(only(cart).status()).isEqualTo(AttemptStatus.PENDING);
        }

        @Test
        void aDifferentFingerprintAbandonsTheStaleAttemptAndOpensANewOne() {
            Cart cart = openCart();
            UUID stale = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

            PendingPayment fresh = cart.startPayment(pixOrder(), afterLease, LEASE);

            assertThat(fresh.attemptId()).isNotEqualTo(stale);
            assertThat(cart.attempts()).hasSize(2);
            assertThat(cart.attempts().get(0).id()).isEqualTo(stale);
            assertThat(cart.attempts().get(0).status()).isEqualTo(AttemptStatus.ABANDONED);
            assertThat(cart.attempts().get(0).completedAt()).isEqualTo(afterLease);
            assertThat(cart.attempts().get(1).id()).isEqualTo(fresh.attemptId());
            assertThat(cart.attempts().get(1).status()).isEqualTo(AttemptStatus.PENDING);
        }

        @Test
        void anAbandonedAttemptCanNoLongerBeApproved() {
            Cart cart = openCart();
            UUID stale = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            cart.startPayment(pixOrder(), NOW.plus(LEASE).plusSeconds(1), LEASE);

            assertThatThrownBy(() -> cart.approve(stale, "AUTH-1", LATER)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void releaseLeaseKeepsTheAttemptPendingAndFreesTheCartAtOnce() {
            Cart cart = openCart();
            UUID attemptId = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();

            cart.releaseLease(attemptId, LATER);

            assertThat(only(cart).status()).isEqualTo(AttemptStatus.PENDING);
            assertThat(cart.isProcessing(LATER)).isFalse();
            assertThat(cart.startPayment(creditOrder(), LATER, LEASE).attemptId()).isEqualTo(attemptId);
        }

        @Test
        void releaseLeaseOfAnUnknownAttemptFails() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);

            assertThatThrownBy(() -> cart.releaseLease(UUID.randomUUID(), LATER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Outcome {

        @Test
        void approveMakesTheCartPaidAndRecordsTheAttempt() {
            Cart cart = openCart();
            UUID attemptId = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();

            cart.approve(attemptId, "AUTH-1", LATER);

            assertThat(cart.status()).isEqualTo(CartStatus.PAID);
            assertThat(cart.paidAt()).isEqualTo(LATER);
            assertThat(cart.processingLeaseUntil()).isNull();
            PaymentAttempt attempt = cart.approvedAttempt().orElseThrow();
            assertThat(attempt.id()).isEqualTo(attemptId);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.APPROVED);
            assertThat(attempt.transactionId()).isEqualTo("AUTH-1");
            assertThat(attempt.completedAt()).isEqualTo(LATER);
            assertThat(attempt.startedAt()).isEqualTo(NOW);
        }

        @Test
        void approveRaisesOneApprovedEventWithTheCartFacts() {
            Cart cart = Cart.create(UUID.randomUUID(), List.of(premium(), basic()), allMethods(), NOW, TTL);
            UUID attemptId = cart.startPayment(pixOrder(), NOW, LEASE).attemptId();

            cart.approve(attemptId, "E999", LATER);

            assertThat(cart.newEvents()).singleElement().isInstanceOfSatisfying(PaymentApproved.class, event -> {
                assertThat(event.cartId()).isEqualTo(cart.id());
                assertThat(event.occurredAt()).isEqualTo(LATER);
                assertThat(event.methodType()).isEqualTo(PaymentMethodType.PIX);
                assertThat(event.amount()).isEqualTo(Money.of("59.80", "BRL"));
                assertThat(event.productSlugs()).containsExactly("premium", "basico");
                assertThat(event.transactionId()).isEqualTo("E999");
                assertThat(event.eventId()).isNotNull();
            });
        }

        @Test
        void aPaidCartCannotBePaidAgain() {
            Cart cart = openCart();
            cart.approve(cart.startPayment(creditOrder(), NOW, LEASE).attemptId(), "AUTH-1", LATER);

            assertFails(cart, creditOrder(), LATER.plusSeconds(1), CheckoutError.CART_ALREADY_PAID);
            assertFails(cart, pixOrder(), LATER.plus(LEASE).plusSeconds(1), CheckoutError.CART_ALREADY_PAID);
        }

        @Test
        void approveTwiceFails() {
            Cart cart = openCart();
            UUID attemptId = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            cart.approve(attemptId, "AUTH-1", LATER);

            assertThatThrownBy(() -> cart.approve(attemptId, "AUTH-2", LATER))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(cart.approvedAttempt().orElseThrow().transactionId()).isEqualTo("AUTH-1");
        }

        @Test
        void approveAnUnknownAttemptFails() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);

            assertThatThrownBy(() -> cart.approve(UUID.randomUUID(), "AUTH-1", LATER))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(cart.status()).isEqualTo(CartStatus.PROCESSING);
        }

        @Test
        void declineMarksTheCartDeclinedAndRecordsTheReason() {
            Cart cart = openCart();
            UUID attemptId = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();

            cart.decline(attemptId, "card refused", LATER);

            assertThat(cart.status()).isEqualTo(CartStatus.DECLINED);
            assertThat(cart.processingLeaseUntil()).isNull();
            assertThat(cart.paidAt()).isNull();
            assertThat(cart.approvedAttempt()).isEmpty();
            PaymentAttempt attempt = only(cart);
            assertThat(attempt.status()).isEqualTo(AttemptStatus.DECLINED);
            assertThat(attempt.declineReason()).isEqualTo("card refused");
            assertThat(attempt.completedAt()).isEqualTo(LATER);
            assertThat(cart.newEvents()).singleElement().isInstanceOfSatisfying(PaymentDeclined.class, event -> {
                assertThat(event.cartId()).isEqualTo(cart.id());
                assertThat(event.reason()).isEqualTo("card refused");
                assertThat(event.methodType()).isEqualTo(PaymentMethodType.CREDIT_CARD);
                assertThat(event.amount()).isEqualTo(Money.of("39.90", "BRL"));
            });
        }

        @Test
        void aDeclinedCartIsPayableAgainWithANewAttempt() {
            Cart cart = openCart();
            UUID declined = cart.startPayment(creditOrder(), NOW, LEASE).attemptId();
            cart.decline(declined, "card refused", LATER);

            PendingPayment retry = cart.startPayment(creditOrder(), LATER.plusSeconds(1), LEASE);

            assertThat(retry.attemptId()).isNotEqualTo(declined);
            assertThat(cart.status()).isEqualTo(CartStatus.PROCESSING);
            assertThat(cart.attempts()).extracting(PaymentAttempt::status)
                    .containsExactly(AttemptStatus.DECLINED, AttemptStatus.PENDING);
        }

        @Test
        void aDeclinedCartCanBePaidWithAnotherMethodAndThenIsPaid() {
            Cart cart = openCart();
            cart.decline(cart.startPayment(creditOrder(), NOW, LEASE).attemptId(), "card refused", LATER);

            PendingPayment pix = cart.startPayment(pixOrder(), LATER.plusSeconds(1), LEASE);
            cart.approve(pix.attemptId(), "E999", LATER.plusSeconds(2));

            assertThat(cart.status()).isEqualTo(CartStatus.PAID);
            assertThat(cart.approvedAttempt().orElseThrow().methodType()).isEqualTo(PaymentMethodType.PIX);
        }
    }

    @Nested
    class Lookup {

        @Test
        void attemptByIdempotencyKeyReturnsTheLatestAttemptWithThatKey() {
            Cart cart = openCart();
            UUID declined = cart.startPayment(creditOrder("k"), NOW, LEASE).attemptId();
            cart.decline(declined, "card refused", LATER);
            UUID second = cart.startPayment(creditOrder("k"), LATER.plusSeconds(1), LEASE).attemptId();

            assertThat(cart.attemptByIdempotencyKey("k")).map(PaymentAttempt::id).contains(second);
        }

        @Test
        void attemptByIdempotencyKeyIsEmptyForNullOrUnknownKeys() {
            Cart cart = openCart();
            cart.startPayment(creditOrder(), NOW, LEASE);

            assertThat(cart.attemptByIdempotencyKey(null)).isEmpty();
            assertThat(cart.attemptByIdempotencyKey("unknown")).isEmpty();
        }

        @Test
        void markPersistedStoresTheVersionAndClearsTheEvents() {
            Cart cart = openCart();
            cart.decline(cart.startPayment(creditOrder(), NOW, LEASE).attemptId(), "card refused", LATER);
            assertThat(cart.newEvents()).hasSize(1);

            cart.markPersisted(3L);

            assertThat(cart.version()).isEqualTo(3L);
            assertThat(cart.newEvents()).isEmpty();
        }

        @Test
        void restoredCartKeepsItsStateAndRulesApply() {
            Cart original = openCart();
            original.approve(original.startPayment(creditOrder(), NOW, LEASE).attemptId(), "AUTH-1", LATER);

            Cart restored = Cart.restore(original.id(), original.items(), original.paymentMethods(), original.total(),
                    original.createdAt(), original.expiresAt(), original.status(), original.processingLeaseUntil(),
                    original.attempts(), original.paidAt(), 7L);

            assertThat(restored.version()).isEqualTo(7L);
            assertThat(restored.approvedAttempt()).isPresent();
            assertFails(restored, creditOrder(), LATER.plusSeconds(1), CheckoutError.CART_ALREADY_PAID);
        }
    }
}
