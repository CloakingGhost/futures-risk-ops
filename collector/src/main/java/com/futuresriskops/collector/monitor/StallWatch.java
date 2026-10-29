package com.futuresriskops.collector.monitor;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수신 공백(조용한 중단) 감지. 연결과 구독은 살아 있는데 체결이 {@code threshold} 넘게 오지 않으면 한 번 경고하고 센다.
 * 체결이 다시 오면 얼마 동안 멈췄는지 남긴다.
 *
 * <p>체결을 기다려야 하는 때(구독 중이고 세션 안)에만 판단한다. 기준 시각은 마지막 체결과 구독 시각 중 늦은 쪽이라,
 * 재접속 직후나 장 시작 직후에 바로 공백으로 세지 않는다. 연결을 다시 맺지는 않는다(감지만 한다).
 */
public final class StallWatch {

    private static final Logger LOG = LoggerFactory.getLogger(StallWatch.class);

    private final Duration threshold;
    private Instant lastActivity;
    /** 공백으로 판정했으면 조용해지기 시작한 시각, 아니면 null. */
    private Instant stalledSince;
    private long stalls;
    private Instant lastTradeAt;

    public StallWatch(Duration threshold) {
        this.threshold = threshold;
    }

    public synchronized void onTrade(Instant at) {
        lastTradeAt = at;
        lastActivity = at;
        if (stalledSince != null) {
            LOG.info("trades resumed after {} ms of silence", Duration.between(stalledSince, at).toMillis());
            stalledSince = null;
        }
    }

    /** 새로 구독했을 때(재접속 포함). 공백 기준을 지금으로 당긴다. */
    public synchronized void onSubscribed(Instant at) {
        lastActivity = at;
        stalledSince = null;
    }

    /**
     * 주기적으로 부른다.
     *
     * @param expectTrades 지금 체결이 와야 하는가(구독 중이고 세션 안)
     * @return 이번 호출에서 새 공백을 찾았으면 true
     */
    public synchronized boolean check(Instant now, boolean expectTrades) {
        if (!expectTrades || lastActivity == null) {
            stalledSince = null;
            return false;
        }
        if (stalledSince != null || Duration.between(lastActivity, now).compareTo(threshold) <= 0) {
            return false;
        }
        stalledSince = lastActivity; // 조용해지기 시작한 때
        stalls++;
        LOG.warn("no trades for {} ms while subscribed (stall #{}); connection looks alive but data stopped",
                Duration.between(lastActivity, now).toMillis(), stalls);
        return true;
    }

    public synchronized long stallCount() {
        return stalls;
    }

    public synchronized boolean stalled() {
        return stalledSince != null;
    }

    /** 마지막으로 체결을 받은 시각. 없으면 null. Day 3 collector_last_trade_received_timestamp_seconds 의 재료. */
    public synchronized Instant lastTradeAt() {
        return lastTradeAt;
    }
}
