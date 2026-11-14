package io.github.magnolett.checkoutbff.integration.cache;

import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis is an optimization: every failure of it must look like a miss to the caller. */
class RedisJsonCacheTest {

    private static final Product PREMIUM = new Product("premium", "Plano Premium", "Catálogo completo",
            Money.of("39.90", "BRL"));

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final RedisJsonCache cache = new RedisJsonCache(redis, jsonMapper);

    @BeforeEach
    void wire() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void hitIsReadBackAsTheDomainObject() {
        when(values.get("k")).thenReturn(Mono.just(jsonMapper.writeValueAsString(PREMIUM)));

        StepVerifier.create(cache.get("k", Product.class)).expectNext(PREMIUM).verifyComplete();
    }

    @Test
    void redisDownIsAMiss() {
        when(values.get("k")).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        StepVerifier.create(cache.get("k", Product.class)).verifyComplete();
    }

    @Test
    void slowRedisIsAMissAfterTheDeadline() {
        when(values.get("k")).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> cache.get("k", Product.class))
                .thenAwait(RedisJsonCache.TIMEOUT.plusMillis(1))
                .verifyComplete();
    }

    @Test
    void valueThatNoLongerParsesIsAMiss() {
        when(values.get("k")).thenReturn(Mono.just("{\"slug\":"));

        StepVerifier.create(cache.get("k", Product.class)).verifyComplete();
    }

    @Test
    void writeFailureIsSwallowed() {
        when(values.set(eq("k"), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        StepVerifier.create(cache.put("k", PREMIUM, Duration.ofMinutes(5))).verifyComplete();
    }
}
