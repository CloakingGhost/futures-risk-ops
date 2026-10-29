package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static long secs(Duration d) {
        return d.toSeconds();
    }

    @Test
    void doublesFromOneSecondUpToThirtyWithoutJitter() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ZERO, new Random(1));
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            waits.add(secs(b.next()));
        }
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), waits);
    }

    @Test
    void resetStartsOverAtOneSecond() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ZERO, new Random(1));
        b.next();
        b.next();
        b.next();
        b.reset();
        assertEquals(Duration.ofSeconds(1), b.next());
    }

    @Test
    void jitterAddsZeroToFiveHundredMillis() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMillis(500), new Random(42));
        boolean sawNonZero = false;
        for (int i = 0; i < 200; i++) {
            b.reset();
            long jitter = b.next().toMillis() - 1000;
            assertTrue(jitter >= 0 && jitter <= 500, "jitter out of range: " + jitter);
            sawNonZero |= jitter > 0;
        }
        assertTrue(sawNonZero);
    }

    @Test
    void neverWaitsLongerThanMaxPlusJitter() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMillis(500), new Random(3));
        for (int i = 0; i < 100; i++) {
            assertTrue(b.next().compareTo(Duration.ofMillis(30_500)) <= 0);
        }
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, new Random()));
        assertThrows(IllegalArgumentException.class,
                () -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ZERO, new Random()));
    }
}
