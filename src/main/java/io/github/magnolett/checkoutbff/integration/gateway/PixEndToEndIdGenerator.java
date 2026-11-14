package io.github.magnolett.checkoutbff.integration.gateway;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * Simulated Pix end-to-end id, in the format of the Brazilian instant payment system: "E", the 8-digit ISPB of the
 * institution, the date and time in UTC (yyyyMMddHHmm) and 11 random alphanumeric characters, 32 in total. The ISPB
 * used here is fictional.
 */
final class PixEndToEndIdGenerator {

    static final int LENGTH = 32;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int RANDOM_PART = 11;

    private final String ispb;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    PixEndToEndIdGenerator(String ispb, Clock clock) {
        this.ispb = ispb;
        this.clock = clock;
    }

    String next() {
        StringBuilder id = new StringBuilder(LENGTH).append('E').append(ispb).append(TIMESTAMP.format(clock.instant()));
        for (int i = 0; i < RANDOM_PART; i++) {
            id.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return id.toString();
    }
}
