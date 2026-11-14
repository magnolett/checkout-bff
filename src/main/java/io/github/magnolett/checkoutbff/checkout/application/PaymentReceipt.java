package io.github.magnolett.checkoutbff.checkout.application;

import io.github.magnolett.checkoutbff.checkout.domain.Cart;
import io.github.magnolett.checkoutbff.checkout.domain.Money;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentAttempt;
import io.github.magnolett.checkoutbff.checkout.domain.PaymentMethodType;
import io.github.magnolett.checkoutbff.checkout.domain.Product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Result of an approved checkout: what the success screen needs and nothing more. */
public record PaymentReceipt(
        UUID cartId,
        Instant paidAt,
        PaymentMethodType methodType,
        String methodName,
        String lastDigits,
        String pixCode,
        Money amount,
        List<Product> products) {

    public static PaymentReceipt from(Cart cart, PaymentAttempt approvedAttempt) {
        String pixCode = approvedAttempt.methodType() == PaymentMethodType.PIX ? approvedAttempt.transactionId() : null;
        return new PaymentReceipt(cart.id(), cart.paidAt(), approvedAttempt.methodType(), approvedAttempt.methodName(),
                approvedAttempt.lastDigits(), pixCode, approvedAttempt.amount(), cart.items());
    }
}
