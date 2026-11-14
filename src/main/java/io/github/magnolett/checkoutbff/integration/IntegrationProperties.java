package io.github.magnolett.checkoutbff.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the adapters: where the external services are, how long to wait for them and how long their answers
 * stay cached, the simulated gateway, and how long abandoned carts are kept.
 */
@ConfigurationProperties("integration")
public record IntegrationProperties(
        Endpoint productsService,
        Endpoint paymentMethodsService,
        @DefaultValue Gateway paymentGateway,
        @DefaultValue Persistence persistence) {

    public IntegrationProperties {
        Objects.requireNonNull(productsService, "integration.products-service must be configured");
        Objects.requireNonNull(paymentMethodsService, "integration.payment-methods-service must be configured");
    }

    /**
     * An external HTTP service.
     *
     * @param baseUrl  where it answers
     * @param timeout  deadline of one call; each retry gets a new one
     * @param cacheTtl how long its answers stay in Redis
     */
    public record Endpoint(URI baseUrl, @DefaultValue("1s") Duration timeout, @DefaultValue("5m") Duration cacheTtl) {

        public Endpoint {
            Objects.requireNonNull(baseUrl, "base-url");
        }
    }

    /**
     * The simulated gateway.
     *
     * @param pixIspb fictional 8-digit institution code used in the simulated Pix end-to-end id
     * @param latency artificial delay of each authorization, useful to demonstrate a double click
     */
    public record Gateway(@DefaultValue("99999999") String pixIspb, @DefaultValue("0ms") Duration latency) {

        public Gateway {
            if (!pixIspb.matches("\\d{8}")) {
                throw new IllegalArgumentException("integration.payment-gateway.pix-ispb must have 8 digits");
            }
        }
    }

    /** @param abandonedCartRetention how long an unpaid cart is kept after it expires; paid carts are never purged */
    public record Persistence(@DefaultValue("24h") Duration abandonedCartRetention) {
    }
}
