package com.futuresriskops.collector.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.rest.DailyBar;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.TickTrade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DB 없이 무엇을 요청하는지, 언제 건너뛰는지, 언제 실행하는지 본다. 일봉 값은 모두 지어낸 합성값이다. */
class DailyCloseJobTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DailyCloseProperties PROPS = new DailyCloseProperties(true, LocalTime.of(16, 0),
            Duration.ofMinutes(15), 5, List.of("F", "CM"), false);

    private final SessionCalendar calendar = TestCalendars.fixed();

    private static Instant kst(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(KST).toInstant();
    }

    private record Call(String market, String symbol, LocalDate from, LocalDate to) {
    }

    /** 요청을 기록하고, failMarket 이면 실패하는 가짜 KIS. */
    private static final class FakeSource implements DailyBarSource {
        final List<Call> calls = new ArrayList<>();
        String failMarket;

        @Override
        public List<DailyBar> fetch(String marketCode, String symbol, LocalDate from, LocalDate to) {
            calls.add(new Call(marketCode, symbol, from, to));
            if (marketCode.equals(failMarket)) {
                throw new IllegalStateException("daily bar request failed with HTTP 500");
            }
            return List.of(new DailyBar(to, new BigDecimal("400.00"), new BigDecimal("401.00"), new BigDecimal("399.00"),
                    new BigDecimal("400.50"), 1000, new BigDecimal("50000000"), "N"));
        }
    }

    /** upsert 한 것을 DB 없이 모으는 저장소. */
    private static final class RecordingRepository extends DailyBarRepository {
        final List<String> written = new ArrayList<>();

        RecordingRepository() {
            super(null, s -> 1L);
        }

        @Override
        public int upsert(String symbol, String market, TickTrade.Source source, List<DailyBar> bars, Instant fetchedAt) {
            written.add(market + ":" + symbol + ":" + source + ":" + bars.size());
            return bars.size();
        }
    }

    private DailyCloseJob job(DailyBarSource source, DailyBarRepository repo, Instant now) {
        return new DailyCloseJob(source, repo, calendar, FuturesContracts::empty, TickTrade.Source.KIS,
                Clock.fixed(now, ZoneOffset.UTC), PROPS);
    }

    @Test
    void asksTheFrontMonthInBothMarketsForTheLastFiveTradingDays() {
        var source = new FakeSource();
        var repo = new RecordingRepository();

        // 2026-11-16 월. 영업일 5개: 11/10 화, 11/11 수, 11/12 목, 11/13 금, 11/16 월
        var result = job(source, repo, kst("2026-11-16", "16:00")).runOnce();

        assertTrue(result.ran());
        assertEquals(List.of(
                new Call("F", "A01612", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16)),
                new Call("CM", "A01612", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16))), source.calls);
        assertEquals(List.of("F:A01612:KIS:1", "CM:A01612:KIS:1"), repo.written);
        assertEquals(new DailyCloseJob.Result(true, 2, 0, 2), result);
    }

    @Test
    void theNextMonthContractIsAskedToo_theDayBeforeExpiry() {
        var source = new FakeSource();

        // 2026-12 만기일은 12/10(둘째 목요일). 만기 전날 12/9 부터 다음 결제월물(A01703)도 받는다
        job(source, new RecordingRepository(), kst("2026-12-09", "16:00")).runOnce();

        assertEquals(List.of("A01612", "A01612", "A01703", "A01703"), source.calls.stream().map(Call::symbol).toList());
    }

    @Test
    void theScheduledRunSkipsWeekendsAndHolidaysWithoutAnyRequest() {
        var source = new FakeSource();
        var repo = new RecordingRepository();

        assertFalse(job(source, repo, kst("2026-11-14", "16:00")).runScheduled().ran(), "Saturday");
        assertFalse(job(source, repo, kst("2026-12-25", "16:00")).runScheduled().ran(), "test holiday");
        assertTrue(source.calls.isEmpty());
        assertTrue(repo.written.isEmpty());
    }

    private static LocalDate lastTo(FakeSource source) {
        return source.calls.get(source.calls.size() - 1).to();
    }

    @Test
    void duringTheSessionTodayIsLeftOutBecauseItIsStillInProgress() {
        var source = new FakeSource();

        // 2026-11-16 월 10:00, 주간장 진행 중. 직전 거래일(11/13 금)까지만, 영업일 5개: 11/9 월 ~ 11/13 금
        job(source, new RecordingRepository(), kst("2026-11-16", "10:00")).runOnce();

        assertEquals(new Call("F", "A01612", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13)), source.calls.get(0));
    }

    @Test
    void todayIsIncludedFromTheRunTimeOn() {
        var source = new FakeSource();

        job(source, new RecordingRepository(), kst("2026-11-16", "15:59")).runOnce();
        assertEquals(LocalDate.of(2026, 11, 13), lastTo(source), "one minute before the run time");

        job(source, new RecordingRepository(), kst("2026-11-16", "16:00")).runOnce();
        assertEquals(LocalDate.of(2026, 11, 16), lastTo(source), "at the run time");
    }

    @Test
    void onALateCloseDayTodayWaitsForTheLaterRunTime() {
        var source = new FakeSource();

        // 2026-11-19 는 주간장이 16:45 에 끝난다(테스트 캘린더). 실행 시각은 17:00
        job(source, new RecordingRepository(), kst("2026-11-19", "16:30")).runOnce();
        assertEquals(LocalDate.of(2026, 11, 18), lastTo(source));

        job(source, new RecordingRepository(), kst("2026-11-19", "17:00")).runOnce();
        assertEquals(LocalDate.of(2026, 11, 19), lastTo(source));
    }

    @Test
    void startedOnAWeekendOrHolidayItCatchesUpToTheLastTradingDay() {
        var source = new FakeSource();

        assertTrue(job(source, new RecordingRepository(), kst("2026-11-14", "10:00")).runOnce().ran(), "Saturday");
        assertEquals(LocalDate.of(2026, 11, 13), lastTo(source));

        job(source, new RecordingRepository(), kst("2026-12-25", "10:00")).runOnce(); // 테스트 휴장일(금)
        assertEquals(LocalDate.of(2026, 12, 24), lastTo(source));
    }

    @Test
    void theContractsAreThoseOfTheLastDayBeingFetchedNotOfToday() {
        var source = new FakeSource();

        // 12월물 만기는 12/10(둘째 목요일). 토요일 12/12 에 켜면 받는 마지막 날은 12/11 이고 그날 근월물은 다음 결제월물(A01703)이다
        job(source, new RecordingRepository(), kst("2026-12-12", "10:00")).runOnce();

        assertEquals(List.of("A01703"), source.calls.stream().map(Call::symbol).distinct().toList());
    }

    @Test
    void aSuccessfulRunSetsLastSuccess() {
        Instant now = kst("2026-11-16", "16:00");
        var job = job(new FakeSource(), new RecordingRepository(), now);

        assertNull(job.lastSuccess());
        job.runOnce();
        assertEquals(now, job.lastSuccess());
    }

    @Test
    void aFailedRequestKeepsGoingButIsNotASuccess() {
        var source = new FakeSource();
        source.failMarket = "CM";
        var repo = new RecordingRepository();
        var job = job(source, repo, kst("2026-11-16", "16:00"));

        var result = job.runOnce();

        assertEquals(new DailyCloseJob.Result(true, 2, 1, 1), result);
        assertEquals(List.of("F:A01612:KIS:1"), repo.written, "the other market is still stored");
        assertNull(job.lastSuccess(), "a failed request must not look like a success");
    }

    @Test
    void aRunWithoutSuccessKeepsThePreviousLastSuccess() {
        var source = new FakeSource();
        Instant first = kst("2026-11-13", "16:00");
        var repo = new RecordingRepository();
        var ok = job(source, repo, first);
        ok.runOnce();
        assertEquals(first, ok.lastSuccess());

        source.failMarket = "F";
        ok.runOnce(); // 같은 시계(고정)로 다시 돌려도 실패는 lastSuccess 를 바꾸지 않는다
        assertEquals(first, ok.lastSuccess());
    }

    @Test
    void runsAt1600OnNormalDaysAndWaitsForALateClose() {
        var job = job(new FakeSource(), new RecordingRepository(), kst("2026-11-16", "09:00"));

        assertEquals(kst("2026-11-16", "16:00"), job.nextRunAfter(kst("2026-11-16", "10:00")));
        assertEquals(kst("2026-11-17", "16:00"), job.nextRunAfter(kst("2026-11-16", "16:00")), "at the run time itself, the next one");
        // 2026-11-19 은 주간장이 16:45 에 끝난다(테스트 캘린더). 마감 15분 뒤인 17:00
        assertEquals(kst("2026-11-19", "17:00"), job.nextRunAfter(kst("2026-11-19", "09:00")));
        assertEquals(kst("2026-11-20", "16:00"), job.nextRunAfter(kst("2026-11-19", "17:00")));
    }

    @Test
    void skipsWeekendsAndHolidaysWhenSchedulingTheNextRun() {
        var job = job(new FakeSource(), new RecordingRepository(), kst("2026-11-16", "09:00"));

        assertEquals(kst("2026-11-16", "16:00"), job.nextRunAfter(kst("2026-11-13", "16:00")), "Friday after the run -> Monday");
        // 12/25 금은 휴장, 26·27 은 주말
        assertEquals(kst("2026-12-28", "16:00"), job.nextRunAfter(kst("2026-12-24", "17:00")));
    }

    @Test
    void propertiesRejectBadValuesAtStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 0, List.of("F"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 101, List.of("F"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5, List.of("O"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5, List.of(), false));
    }
}
