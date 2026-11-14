package io.github.magnolett.checkoutbff.integration.gateway;

import io.github.magnolett.checkoutbff.checkout.domain.PaymentAuthorization;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.PendingPayment;
import io.github.magnolett.checkoutbff.checkout.port.PaymentGateway;
import io.github.magnolett.checkoutbff.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stand-in for a payment provider, as the challenge asks (no real gateway).
 *
 * <ul>
 *   <li>a card ending in 0002 is declined, so the frontend can show the error screen;</li>
 *   <li>any other card is approved;</li>
 *   <li>Pix is approved with a simulated end-to-end id.</li>
 * </ul>
 *
 * <p>Like a real provider, it is idempotent by attempt id: the same attempt sent twice gets the first answer. That is
 * what makes resuming a pending attempt safe.
 */
@Slf4j
@Component
class SimulatedPaymentGateway implements PaymentGateway {

    static final String DECLINED_CARD_SUFFIX = "0002";
    private static final int REMEMBERED_ATTEMPTS = 10_000;

    private final PixEndToEndIdGenerator pixIds;
    private final Duration latency;
    private final Map<UUID, PaymentAuthorization> answers = Collections.synchronizedMap(
            new LinkedHashMap<UUID, PaymentAuthorization>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, PaymentAuthorization> eldest) {
                    return size() > REMEMBERED_ATTEMPTS;
                }
            });

    SimulatedPaymentGateway(IntegrationProperties properties, Clock clock) {
        this.pixIds = new PixEndToEndIdGenerator(properties.paymentGateway().pixIspb(), clock);
        this.latency = properties.paymentGateway().latency();
    }

    @Override
    public Mono<PaymentAuthorization> authorize(PendingPayment payment) {
        Mono<PaymentAuthorization> answer = Mono.fromSupplier(
                        () -> answers.computeIfAbsent(payment.attemptId(), attemptId -> decide(payment)))
                .doOnNext(authorization -> log.info("simulated authorization attemptId={} method={} outcome={}",
                        payment.attemptId(), payment.methodType(), authorization.getClass().getSimpleName()));
        return latency.isZero() ? answer : answer.delayElement(latency);
    }

    private PaymentAuthorization decide(PendingPayment payment) {
        if (payment.methodType() == PaymentMethodType.PIX) {
            return new PaymentAuthorization.Approved(pixIds.next());
        }
        if (payment.card().number().endsWith(DECLINED_CARD_SUFFIX)) {
            return new PaymentAuthorization.Declined("declined by the issuer");
        }
        return new PaymentAuthorization.Approved("SIM-" + payment.attemptId());
    }
}
