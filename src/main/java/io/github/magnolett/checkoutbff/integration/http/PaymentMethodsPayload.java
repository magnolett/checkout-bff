package io.github.magnolett.checkoutbff.integration.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** What the payment methods service returns; fields the checkout does not use are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PaymentMethodsPayload(List<Item> paymentMethods) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String code, String displayName, Boolean enabled) {
    }
}
