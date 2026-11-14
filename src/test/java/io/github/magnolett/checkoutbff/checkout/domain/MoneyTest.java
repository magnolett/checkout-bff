package io.github.magnolett.checkoutbff.checkout.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void keepsScaleTwoWhateverTheInputScale() {
        assertThat(Money.of("39.9", "BRL").amount()).isEqualTo(new BigDecimal("39.90"));
        assertThat(Money.of("10", "BRL").amount().scale()).isEqualTo(2);
        assertThat(Money.of("39.90", "BRL").amount().toPlainString()).isEqualTo("39.90");
    }

    @Test
    void amountsWithTheSameValueAreEqualRegardlessOfInputScale() {
        assertThat(Money.of("1.0", "BRL")).isEqualTo(Money.of("1.00", "BRL"));
    }

    @Test
    void acceptsZero() {
        assertThat(Money.of("0", "BRL").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of("-0.01", "BRL")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankOrNullCurrency() {
        assertThatThrownBy(() -> Money.of("1.00", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.00", "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> new Money(null, "BRL")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void doesNotRoundSilently() {
        assertThatThrownBy(() -> Money.of("39.905", "BRL")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void plusAddsAmountsInTheSameCurrency() {
        Money total = Money.of("19.90", "BRL").plus(Money.of("39.90", "BRL"));

        assertThat(total).isEqualTo(Money.of("59.80", "BRL"));
    }

    @Test
    void plusRejectsMixedCurrencies() {
        assertThatThrownBy(() -> Money.of("19.90", "BRL").plus(Money.of("1.00", "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRL")
                .hasMessageContaining("USD");
    }
}
