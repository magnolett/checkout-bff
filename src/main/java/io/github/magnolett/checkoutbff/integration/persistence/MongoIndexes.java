package io.github.magnolett.checkoutbff.integration.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Indexes of the carts collection, created before the application reports itself ready.
 *
 * <ul>
 *   <li>TTL on purgeAt: Mongo deletes unpaid carts on its own; a paid cart has no purgeAt and is kept.</li>
 *   <li>Partial index on outboxClaimedUntil, only for carts with events waiting: the relay query reads an index
 *       that holds almost nothing, however large the collection grows.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MongoIndexes implements ApplicationRunner {

    private static final Duration STARTUP_DEADLINE = Duration.ofSeconds(30);

    private final ReactiveMongoTemplate mongo;

    @Override
    public void run(ApplicationArguments args) {
        ReactiveIndexOperations carts = mongo.indexOps(CartDocument.COLLECTION);
        Flux.concat(
                        carts.createIndex(new Index().on("purgeAt", Sort.Direction.ASC)
                                .expire(Duration.ZERO)
                                .named("carts_purge_ttl")),
                        carts.createIndex(new Index().on("outboxClaimedUntil", Sort.Direction.ASC)
                                .partial(PartialIndexFilter.of(Criteria.where("outbox.0").exists(true)))
                                .named("carts_outbox_waiting")))
                .doOnNext(name -> log.info("mongo index ready name={}", name))
                .blockLast(STARTUP_DEADLINE);
    }
}
