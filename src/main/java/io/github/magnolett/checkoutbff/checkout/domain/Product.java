package io.github.magnolett.checkoutbff.checkout.domain;

import java.util.Objects;

/** Product as the checkout sees it. The cart keeps a snapshot, so the price charged is the price shown. */
public record Product(String slug, String name, String description, Money price) {

    public Product {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(price, "price");
    }
}
