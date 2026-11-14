package io.github.magnolett.checkoutbff.integration.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * JSON values in Redis with a short deadline. Redis is an optimization, never a dependency: a read or a write that
 * fails, or takes longer than the deadline, is logged and the caller goes to the source.
 */
@Slf4j
@Component
public class RedisJsonCache {

    static final Duration TIMEOUT = Duration.ofMillis(500);

    private final ReactiveStringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public RedisJsonCache(ReactiveStringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /** The cached value, or empty on a miss, on a value that no longer parses, or when Redis fails. */
    public <T> Mono<T> get(String key, Class<T> type) {
        return redis.opsForValue().get(key)
                .timeout(TIMEOUT)
                .map(json -> jsonMapper.readValue(json, type))
                .onErrorResume(error -> {
                    log.warn("cache read failed, going to the source key={} cause={}", key, error.toString());
                    return Mono.empty();
                });
    }

    /** Stores the value; a failure is logged and swallowed. */
    public Mono<Void> put(String key, Object value, Duration ttl) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(value))
                .flatMap(json -> redis.opsForValue().set(key, json, ttl))
                .timeout(TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("cache write failed key={} cause={}", key, error.toString());
                    return Mono.empty();
                })
                .then();
    }
}
