package io.github.magnolett.checkoutbff.integration.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.Product;

import java.math.BigDecimal;

/**
 * What the products service returns. Only the fields the checkout uses are read; anything else (id, metadata,
 * billing period) is ignored, so the service can grow without breaking the BFF.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ProductPayload(String slug, String title, String summary, Pricing pricing) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pricing(BigDecimal amount, String currency) {
    }

    Product toProduct(String requestedSlug) {
        if (title == null || title.isBlank() || pricing == null || pricing.amount() == null
                || pricing.currency() == null || pricing.currency().isBlank()) {
            throw new IllegalArgumentException("incomplete product " + requestedSlug);
        }
        return new Product(requestedSlug, title.strip(), summary == null ? null : summary.strip(),
                new Money(pricing.amount(), pricing.currency().strip()));
    }
}
