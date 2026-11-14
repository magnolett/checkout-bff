package io.github.magnolett.checkoutbff.integration.persistence;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.port.CartRepository;
import io.github.magnolett.checkoutbff.checkout.port.ConcurrentCartUpdateException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Carts in MongoDB.
 *
 * <p>An update is a single conditional write: it only matches the document if the version is the one that was read
 * (optimistic lock), sets the fields the aggregate changes, increments the version and appends the new events to the
 * outbox. Nothing is replaced wholesale, so the relay can remove published events from the same document without
 * conflicting with a checkout in progress.
 */
@Repository
@RequiredArgsConstructor
class MongoCartRepository implements CartRepository {

    private final ReactiveMongoTemplate mongo;
    private final CartDocumentMapper mapper;

    @Override
    public Mono<Cart> findById(java.util.UUID id) {
        return mongo.findById(id.toString(), CartDocument.class).map(mapper::toDomain);
    }

    @Override
    public Mono<Cart> insert(Cart cart) {
        return mongo.insert(mapper.toDocument(cart))
                .map(saved -> {
                    cart.markPersisted(cart.version());
                    return cart;
                });
    }

    @Override
    public Mono<Cart> update(Cart cart) {
        long readVersion = cart.version();
        Query sameVersion = Query.query(where("_id").is(cart.id().toString()).and("version").is(readVersion));
        Update update = new Update()
                .set("status", cart.status())
                .set("attempts", mapper.attempts(cart))
                .set("processingLeaseUntil", cart.processingLeaseUntil())
                .set("paidAt", cart.paidAt())
                .inc("version", 1);
        Instant purgeAt = mapper.purgeAt(cart);
        if (purgeAt == null) {
            update.unset("purgeAt");
        } else {
            update.set("purgeAt", purgeAt);
        }
        List<OutboxEntry> events = mapper.outbox(cart);
        if (!events.isEmpty()) {
            update.push("outbox").each(events.toArray());
        }
        return mongo.updateFirst(sameVersion, update, CartDocument.class)
                .flatMap(result -> {
                    if (result.getMatchedCount() == 0) {
                        return Mono.error(new ConcurrentCartUpdateException(cart.id()));
                    }
                    cart.markPersisted(readVersion + 1);
                    return Mono.just(cart);
                });
    }
}
