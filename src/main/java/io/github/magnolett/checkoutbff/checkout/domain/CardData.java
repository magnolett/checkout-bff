package io.github.magnolett.checkoutbff.checkout.domain;

import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Card data as typed by the customer. It only lives during the request: the full number and the CVV are never
 * persisted, logged or published. {@link #toString()} is masked for that reason.
 *
 * <p>The Luhn check is deliberately not applied: the card in the challenge example does not pass it, and validating
 * the PAN is the issuer's job. In production the frontend would tokenize the card with the PSP and the BFF would
 * only see a token.
 */
public record CardData(String holderName, String number, String expiry, String cvv) {

    private static final Pattern DIGITS = Pattern.compile("\\d{13,19}");
    private static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])/(\\d{2})");
    private static final Pattern CVV = Pattern.compile("\\d{3,4}");

    public CardData {
        holderName = holderName == null ? null : holderName.strip();
        number = number == null ? null : number.replace(" ", "").replace("-", "");
        expiry = expiry == null ? null : expiry.strip();
        cvv = cvv == null ? null : cvv.strip();
    }

    /** First invalid field, if any. The check covers format and expiry; it never echoes the values. */
    public Optional<String> firstInvalidField(YearMonth currentMonth) {
        if (holderName == null || holderName.isBlank() || holderName.length() > 100) {
            return Optional.of("holderName");
        }
        if (number == null || !DIGITS.matcher(number).matches()) {
            return Optional.of("number");
        }
        if (cvv == null || !CVV.matcher(cvv).matches()) {
            return Optional.of("cvv");
        }
        Optional<YearMonth> expiresAt = expiryMonth();
        if (expiresAt.isEmpty() || expiresAt.get().isBefore(currentMonth)) {
            return Optional.of("expiry");
        }
        return Optional.empty();
    }

    /** Last four digits, the only part of the number that may be stored and shown. */
    public String lastDigits() {
        if (number == null || number.length() < 4) {
            return number;
        }
        return number.substring(number.length() - 4);
    }

    private Optional<YearMonth> expiryMonth() {
        if (expiry == null) {
            return Optional.empty();
        }
        Matcher matcher = EXPIRY.matcher(expiry);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int month = Integer.parseInt(matcher.group(1));
        int year = 2000 + Integer.parseInt(matcher.group(2));
        return Optional.of(YearMonth.of(year, month));
    }

    @Override
    public String toString() {
        return "CardData[number=****" + (lastDigits() == null ? "" : lastDigits()) + "]";
    }
}
