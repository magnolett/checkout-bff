package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.integration.messaging.MessagingProperties;
import io.github.magnolett.checkoutbff.integration.messaging.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Publishes the events that wait in the carts' outbox. An event was saved in the same atomic update as the state
 * change that raised it, so a crash between saving and publishing delays the event instead of losing it.
 *
 * <p>Each run claims one cart at a time (findAndModify on a lease field), publishes its events in order, keyed by
 * cart id, and only then removes them with $pull. Several instances can run the relay: the claim keeps two of them
 * off the same cart. Delivery is at least once (a crash after publishing and before the $pull publishes again), so
 * consumers are idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "messaging.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class OutboxRelay {

    private final ReactiveMongoTemplate mongo;
    private final PaymentEventPublisher publisher;
    private final MessagingProperties properties;
    private final Clock clock;

    /** Spring subscribes to the returned Mono; the next run starts after this one completes. */
    @Scheduled(fixedDelayString = "${messaging.outbox.relay-interval:1s}")
    Mono<Void> relayPendingEvents() {
        return Flux.range(0, properties.outbox().batchSize())
                .concatMap(i -> relayNextCart())
                .takeWhile(Boolean::booleanValue)
                .then();
    }

    /**
     * Relays the events of one cart. False stops the run: either no cart is waiting, or publishing failed, which
     * usually means the broker is down and the next carts would fail the same way.
     */
    Mono<Boolean> relayNextCart() {
        return claimNextCart()
                .flatMap(cart -> Flux.fromIterable(cart.outbox())
                        .concatMap(event -> publisher.publish(cart.id(), event.eventId(), event.eventType(),
                                event.payload()))
                        .then(removePublished(cart))
                        .thenReturn(true)
                        .onErrorResume(error -> {
                            log.warn("outbox publish failed, retried after the claim expires cartId={} cause={}",
                                    cart.id(), error.toString());
                            return Mono.just(false);
                        }))
                .defaultIfEmpty(false);
    }

    private Mono<ClaimedOutbox> claimNextCart() {
        Instant now = clock.instant();
        Query waiting = Query.query(Criteria.where("outbox.0").exists(true)
                .orOperator(Criteria.where("outboxClaimedUntil").isNull(),
                        Criteria.where("outboxClaimedUntil").lt(now)));
        waiting.fields().include("outbox");
        Update claim = Update.update("outboxClaimedUntil", now.plus(properties.outbox().claimDuration()));
        return mongo.findAndModify(waiting, claim, FindAndModifyOptions.options().returnNew(true),
                ClaimedOutbox.class, CartDocument.COLLECTION);
    }

    /** Removes only the events that were published; an event appended meanwhile stays for the next run. */
    private Mono<Void> removePublished(ClaimedOutbox cart) {
        List<String> published = cart.outbox().stream().map(OutboxEntry::eventId).toList();
        Update update = new Update()
                .pull("outbox", new Document("eventId", new Document("$in", published)))
                .unset("outboxClaimedUntil");
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(cart.id())), update, CartDocument.COLLECTION)
                .then();
    }

    record ClaimedOutbox(@Id String id, List<OutboxEntry> outbox) {
    }
}
