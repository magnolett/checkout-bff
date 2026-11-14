package io.github.magnolett.checkoutbff.checkout.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Clock the test moves by hand. */
class TestClock extends Clock {

    private Instant now;

    TestClock(Instant now) {
        this.now = now;
    }

    void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
