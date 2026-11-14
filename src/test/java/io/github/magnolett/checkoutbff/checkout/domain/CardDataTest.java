package io.github.magnolett.checkoutbff.checkout.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class CardDataTest {

    private static final YearMonth OCT_2026 = YearMonth.of(2026, 10);

    private static CardData card(String holder, String number, String expiry, String cvv) {
        return new CardData(holder, number, expiry, cvv);
    }

    private static CardData withNumber(String number) {
        return card("Ana Souza", number, "12/28", "123");
    }

    private static CardData withExpiry(String expiry) {
        return card("Ana Souza", "4111111111114242", expiry, "123");
    }

    private static CardData withCvv(String cvv) {
        return card("Ana Souza", "4111111111114242", "12/28", cvv);
    }

    @Test
    void acceptsTheCardOfTheChallengeExample() {
        // The example card does not pass Luhn, on purpose: there is no Luhn check
        assertThat(card("Ana Souza", "4111111111114242", "12/28", "123").firstInvalidField(OCT_2026)).isEmpty();
    }

    @Test
    void removesSpacesAndDashesFromTheNumber() {
        assertThat(withNumber("4111 1111-1111 4242").number()).isEqualTo("4111111111114242");
        assertThat(withNumber("4111 1111-1111 4242").firstInvalidField(OCT_2026)).isEmpty();
    }

    @Test
    void numberLengthBoundariesAreThirteenToNineteenDigits() {
        assertThat(withNumber("1".repeat(13)).firstInvalidField(OCT_2026)).isEmpty();
        assertThat(withNumber("1".repeat(19)).firstInvalidField(OCT_2026)).isEmpty();
        assertThat(withNumber("1".repeat(12)).firstInvalidField(OCT_2026)).contains("number");
        assertThat(withNumber("1".repeat(20)).firstInvalidField(OCT_2026)).contains("number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abcdefghijklmnop", "411111111111424x", "4111.1111.1111.4242"})
    void rejectsNumbersThatAreNotDigits(String number) {
        assertThat(withNumber(number).firstInvalidField(OCT_2026)).contains("number");
    }

    @Test
    void rejectsNullFields() {
        assertThat(card(null, "4111111111114242", "12/28", "123").firstInvalidField(OCT_2026)).contains("holderName");
        assertThat(card("Ana", null, "12/28", "123").firstInvalidField(OCT_2026)).contains("number");
        assertThat(card("Ana", "4111111111114242", null, "123").firstInvalidField(OCT_2026)).contains("expiry");
        assertThat(card("Ana", "4111111111114242", "12/28", null).firstInvalidField(OCT_2026)).contains("cvv");
    }

    @Test
    void holderNameMustBeNonBlankAndAtMostOneHundredCharacters() {
        assertThat(card("   ", "4111111111114242", "12/28", "123").firstInvalidField(OCT_2026))
                .contains("holderName");
        assertThat(card("A".repeat(100), "4111111111114242", "12/28", "123").firstInvalidField(OCT_2026)).isEmpty();
        assertThat(card("A".repeat(101), "4111111111114242", "12/28", "123").firstInvalidField(OCT_2026))
                .contains("holderName");
    }

    @Test
    void holderNameIsStrippedBeforeTheLengthCheck() {
        CardData card = card("  " + "A".repeat(100) + "  ", "4111111111114242", "12/28", "123");

        assertThat(card.holderName()).hasSize(100);
        assertThat(card.firstInvalidField(OCT_2026)).isEmpty();
    }

    @Test
    void cvvHasThreeOrFourDigits() {
        assertThat(withCvv("123").firstInvalidField(OCT_2026)).isEmpty();
        assertThat(withCvv("1234").firstInvalidField(OCT_2026)).isEmpty();
        assertThat(withCvv("12").firstInvalidField(OCT_2026)).contains("cvv");
        assertThat(withCvv("12345").firstInvalidField(OCT_2026)).contains("cvv");
        assertThat(withCvv("12a").firstInvalidField(OCT_2026)).contains("cvv");
        assertThat(withCvv("").firstInvalidField(OCT_2026)).contains("cvv");
    }

    @Test
    void expiryInTheCurrentMonthIsStillValid() {
        assertThat(withExpiry("10/26").firstInvalidField(OCT_2026)).isEmpty();
    }

    @Test
    void expiryInThePreviousMonthIsInvalid() {
        assertThat(withExpiry("09/26").firstInvalidField(OCT_2026)).contains("expiry");
    }

    @Test
    void expiryComparesYearBeforeMonth() {
        assertThat(withExpiry("12/25").firstInvalidField(OCT_2026)).contains("expiry");
        assertThat(withExpiry("01/27").firstInvalidField(OCT_2026)).isEmpty();
        assertThat(withExpiry("12/26").firstInvalidField(YearMonth.of(2026, 12))).isEmpty();
        assertThat(withExpiry("12/26").firstInvalidField(YearMonth.of(2027, 1))).contains("expiry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"13/28", "00/28", "1/28", "12/2028", "12-28", "1228", "ab/cd", ""})
    void rejectsExpiryOutsideTheMmYyFormat(String expiry) {
        assertThat(withExpiry(expiry).firstInvalidField(OCT_2026)).contains("expiry");
    }

    @Test
    void lastDigitsAreTheLastFourOfTheNormalizedNumber() {
        assertThat(card("Ana", "4111 1111 1111 4242", "12/28", "123").lastDigits()).isEqualTo("4242");
    }

    @Test
    void toStringNeverShowsFullNumberCvvOrHolder() {
        String text = card("Ana Souza", "4111111111114242", "12/28", "987").toString();

        assertThat(text).contains("4242")
                .doesNotContain("4111111111114242")
                .doesNotContain("411111111111")
                .doesNotContain("987")
                .doesNotContain("Ana Souza");
    }
}
