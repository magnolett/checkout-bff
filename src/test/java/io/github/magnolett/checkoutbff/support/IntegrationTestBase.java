package io.github.magnolett.checkoutbff.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mongodb.MongoDBContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Real infrastructure for the integration tests: MongoDB, Redis and Kafka in containers, and the two external
 * services in WireMock, loaded with the same mappings the docker compose uses. Everything starts once per JVM and is
 * shared by every test class; Testcontainers removes the containers when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final String PAYMENT_EVENTS_TOPIC = "checkout.payment-events";

    // Same images as docker-compose.yml, so the tests and the local stack run the same versions.
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:9.0.8");
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.10.3").withExposedPorts(6379);
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");
    protected static final WireMockServer PRODUCTS_SERVICE =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/products-service"));
    protected static final WireMockServer PAYMENT_METHODS_SERVICE =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/payment-methods-service"));

    static {
        Startables.deepStart(MONGO, REDIS, KAFKA).join();
        PRODUCTS_SERVICE.start();
        PAYMENT_METHODS_SERVICE.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("checkout"));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("integration.products-service.base-url", PRODUCTS_SERVICE::baseUrl);
        registry.add("integration.payment-methods-service.base-url", PAYMENT_METHODS_SERVICE::baseUrl);
    }
}
