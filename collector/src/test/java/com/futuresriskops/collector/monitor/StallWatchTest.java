package com.futuresriskops.collector.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StallWatchTest {

    private static final Instant T0 = Instant.parse("2026-10-28T01:00:00Z");
    private final StallWatch w = new StallWatch(Duration.ofSeconds(5));

    private static Instant t(long seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void silenceLongerThanTheThresholdIsOneStall() {
        w.onSubscribed(t(0));
        w.onTrade(t(1));
        assertFalse(w.check(t(6), true));
        assertTrue(w.check(t(7), true));
        assertFalse(w.check(t(20), true), "the same stall is counted once");
        assertEquals(1, w.stallCount());
        assertTrue(w.stalled());
        w.onTrade(t(21));
        assertFalse(w.stalled());
        assertTrue(w.check(t(27), true));
        assertEquals(2, w.stallCount());
    }

    @Test
    void notCountedWhenTradesAreNotExpected() {
        w.onSubscribed(t(0));
        assertFalse(w.check(t(60), false)); // 세션 밖이거나 끊겨 있음
        assertEquals(0, w.stallCount());
    }

    @Test
    void resubscribingResetsTheClock() {
        w.onSubscribed(t(0));
        w.onTrade(t(1));
        w.onSubscribed(t(100)); // 재접속
        assertFalse(w.check(t(104), true));
        assertTrue(w.check(t(106), true));
    }

    @Test
    void nothingBeforeTheFirstSubscription() {
        assertFalse(w.check(t(100), true));
    }
}
