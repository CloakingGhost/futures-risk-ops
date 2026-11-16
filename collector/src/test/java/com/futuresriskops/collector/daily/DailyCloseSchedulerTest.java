package com.futuresriskops.collector.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 정해진 시각의 타이머 경로. 시계를 화요일 15:59:59.7 에 맞춰 흘려보내면 0.3초 뒤인 16:00 에 한 번만 돌고, 다음 실행은 다음 거래일로
     * 잡혀서 바로 다시 돌지 않아야 한다. 이 경로는 실제로는 거래일 16:00 에만 실행되므로 시계를 옮겨서 미리 확인한다.
     */
    @Test
    void theTimerFiresOnceAtTheRunTimeAndPlansTheNextTradingDayWithoutLooping() throws Exception {
        Instant target = LocalDate.of(2026, 11, 17).atTime(15, 59, 59, 700_000_000).atZone(KST).toInstant();
        Clock clock = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), target));
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
                clock, new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5, List.of("F"), false));
        scheduler = new DailyCloseScheduler(job, clock, false);

        scheduler.start();
        assertTrue(requestedTo.isEmpty(), "16:00 has not come yet");
        awaitRequests(1);
        Thread.sleep(700); // 다음 실행은 내일 16:00 이라 더 오지 않는다(바로 다시 도는 반복이 없다)

        assertEquals(List.of(LocalDate.of(2026, 11, 17)), requestedTo, "the scheduled run includes today");
        assertNotNull(job.lastSuccess(), "the run counts as a success");
        assertEquals(LocalDate.of(2026, 11, 17), LocalDate.ofInstant(job.lastSuccess(), KST));
        assertEquals(LocalDate.of(2026, 11, 18), LocalDate.ofInstant(job.nextRunAfter(job.lastSuccess()), KST),
                "the next run is the next trading day");
    }

    /**
     * 타이머가 시계보다 몇 ms 일찍 깨면 now 가 실행 시각 직전이다. 그때 now 기준으로 다음 실행을 구하면 같은 16:00:00 이 또 나와 바로 다시
     * 돈다. 시계를 16:00:00 1ms 전에 고정해 그 상황을 만든다. 한 번만 돌고 다음 거래일로 넘어가야 한다.
     */
    @Test
    void anEarlyTimerDoesNotRunTheSameTimeAgain() throws Exception {
        schedulerAt("2026-11-17T15:59:59.999", false).start();

        awaitRequests(1);
        Thread.sleep(500);

        assertEquals(List.of(LocalDate.of(2026, 11, 17)), requestedTo, "one run, not a loop at the same instant");
    }

    @Test
    void withoutRunOnStartNothingIsRequestedUntilTheScheduledTime() throws Exception {
        schedulerAt("2026-11-16T10:00", false).start();

        Thread.sleep(500);
        assertTrue(requestedTo.isEmpty(), requestedTo.toString());
    }
}
