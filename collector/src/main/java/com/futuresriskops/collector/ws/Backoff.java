package com.futuresriskops.collector.ws;

import java.time.Duration;
import java.util.Random;

/**
 * 재접속 대기 시간. 1, 2, 4, 8 ... 초처럼 두 배씩 늘리다 {@code max} 에서 멈추고, 매번 0~{@code maxJitter} 의 난수를 더한다
 * (여러 수집기가 같은 순간에 몰려 붙지 않게). 연결과 구독에 성공하면 {@link #reset()} 으로 처음부터 다시 센다.
 * 한 스레드에서만 쓴다.
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private final Duration maxJitter;
    private final Random rng;
    private int attempt;

    public Backoff(Duration initial, Duration max, Duration maxJitter, Random rng) {
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0 || maxJitter.isNegative()) {
            throw new IllegalArgumentException("need 0 < initial <= max and maxJitter >= 0");
        }
        this.initial = initial;
        this.max = max;
        this.maxJitter = maxJitter;
        this.rng = rng;
    }

    /** 이번에 기다릴 시간(지터 포함). 부를 때마다 다음 단계로 넘어간다. */
    public Duration next() {
        Duration base = initial;
        for (int i = 0; i < attempt && base.compareTo(max) < 0; i++) {
            base = base.multipliedBy(2);
        }
        if (base.compareTo(max) > 0) {
            base = max;
        }
        if (base.compareTo(max) < 0) {
            attempt++;
        }
        long jitterMs = maxJitter.isZero() ? 0 : rng.nextLong(maxJitter.toMillis() + 1);
        return base.plusMillis(jitterMs);
    }

    public void reset() {
        attempt = 0;
    }
}
