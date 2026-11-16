package com.futuresriskops.collector.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.rest.DailyBar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.TickTrade;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** run-on-start 가 시작 직후 한 번만, 그리고 장중이 아닌 확정 구간까지만 받는지 본다. 값은 합성이고 DB·KIS 는 쓰지 않는다. */
class DailyCloseSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final List<LocalDate> requestedTo = new CopyOnWriteArrayList<>();
    private DailyCloseScheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    private DailyCloseScheduler schedulerAt(String dateTime, boolean runOnStart) {
        Instant now = LocalDate.parse(dateTime.substring(0, 10)).atTime(LocalTime.parse(dateTime.substring(11)))
                .atZone(KST).toInstant();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DailyBarSource source = (market, symbol, from, to) -> {
            requestedTo.add(to);
            return List.<DailyBar>of();
        };
        DailyBarRepository repo = new DailyBarRepository(null, s -> 1L) {
            @Override
            public int upsert(String symbol, String market, TickTrade.Source src, List<DailyBar> bars, Instant at) {
                return bars.size();
            }
        };
        var job = new DailyCloseJob(source, repo, TestCalendars.fixed(), FuturesContracts::empty, TickTrade.Source.KIS,
                clock, new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5, List.of("F"), runOnStart));
        scheduler = new DailyCloseScheduler(job, clock, runOnStart);
        return scheduler;
    }

    private void awaitRequests(int n) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (requestedTo.size() < n) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + n + " request(s), got " + requestedTo);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void runOnStartFetchesOnceRightAwayAndLeavesOutTodayDuringTheSession() throws Exception {
        // 월요일 10:00 주간장 진행 중. 다음 스케줄은 같은 날 16:00 이라 아직 멀다
        schedulerAt("2026-11-16T10:00", true).start();

        awaitRequests(1);
        Thread.sleep(300); // 두 번째 요청이 더 오지 않는지
        assertEquals(List.of(LocalDate.of(2026, 11, 13)), requestedTo);
    }

    @Test
    void withoutRunOnStartNothingIsRequestedUntilTheScheduledTime() throws Exception {
        schedulerAt("2026-11-16T10:00", false).start();

        Thread.sleep(500);
        assertTrue(requestedTo.isEmpty(), requestedTo.toString());
    }
}
