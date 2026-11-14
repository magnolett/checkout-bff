package io.github.magnolett.checkoutbff.checkout.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutPropertiesTest {

    @Test
    void leaseMustBeLongerThanThePaymentTimeout() {
        assertThatThrownBy(() -> new CheckoutProperties(Duration.ofMinutes(30), Duration.ofSeconds(10),
                Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutProperties(Duration.ofMinutes(30), Duration.ofSeconds(10),
                Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsALeaseLongerThanTheTimeout() {
        CheckoutProperties properties = new CheckoutProperties(Duration.ofMinutes(30), Duration.ofSeconds(10),
                Duration.ofSeconds(11));

        assertThat(properties.processingLease()).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void bindsTheDocumentedDefaults() {
        CheckoutProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bind("checkout", CheckoutProperties.class)
                .orElse(null);

        // Nothing configured: Binder returns no bean, so bind a single unrelated key to force the defaults
        CheckoutProperties withDefaults = new Binder(new MapConfigurationPropertySource(Map.of("checkout.cart-ttl", "30m")))
                .bind("checkout", CheckoutProperties.class)
                .get();

        assertThat(bound).isNull();
        assertThat(withDefaults.cartTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(withDefaults.paymentTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(withDefaults.processingLease()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aMisconfiguredLeaseFailsTheBinding() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "checkout.payment-timeout", "20s",
                "checkout.processing-lease", "15s")));

        assertThatThrownBy(() -> binder.bind("checkout", CheckoutProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
