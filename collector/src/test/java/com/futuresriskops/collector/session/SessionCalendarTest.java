package com.futuresriskops.collector.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * 세션 캘린더 규칙. 날짜 기준: 2026-10-30 은 금요일, 2026-12-25(테스트 휴장일)도 금요일.
 * 야간장 거래일 = 다음 거래일 은 가정이다(설정 night_trade_date 로 바꾼다).
 */
class SessionCalendarTest {

    private final SessionCalendar cal = TestCalendars.fixed();

    private static Instant kst(String dateTime) {
        return ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]").toInstant();
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private SessionCalendar.Placement at(String dateTime) {
        return cal.classify(ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]"));
    }

    // ------------------------------------------------------------------ 거래일·세션

    @Test
    void dayTradeBelongsToItsOwnDate() {
        var p = at("2026-10-28T09:00:01");
        assertEquals(TradingSession.DAY, p.session());
        assertEquals(d("2026-10-28"), p.tradeDate());
    }

    @Test
    void nightTradeBeforeMidnightBelongsToTheNextTradingDay() {
        var p = at("2026-10-27T18:00:00");
        assertEquals(TradingSession.NIGHT, p.session());
        assertEquals(d("2026-10-28"), p.tradeDate());
    }

    @Test
    void nightTradeAfterMidnightBelongsToTheSameNightAsBeforeMidnight() {
        assertEquals(at("2026-10-27T23:59:59").tradeDate(), at("2026-10-28T00:00:00").tradeDate());
        var p = at("2026-10-28T05:59:59");
        assertEquals(TradingSession.NIGHT, p.session());
        assertEquals(d("2026-10-28"), p.tradeDate());
    }

    @Test
    void midnightCrossingUsesTheTradeTimeNotTheReceiveTime() {
        var p = cal.place(LocalTime.of(23, 59, 59), kst("2026-10-28T00:00:01"));
        assertEquals(kst("2026-10-27T23:59:59"), p.tradedAt().toInstant());
        assertEquals(TradingSession.NIGHT, p.session());
        assertEquals(d("2026-10-28"), p.tradeDate());
        // 반대로 자정 직후 체결을 자정 직전 수신 시각으로 받는 일(시계 어긋남)은 다음 날 체결로 본다
        var q = cal.place(LocalTime.of(0, 0, 1), kst("2026-10-27T23:59:58"));
        assertEquals(kst("2026-10-28T00:00:01"), q.tradedAt().toInstant());
    }

    @Test
    void fridayNightRunsUntilSaturdaySixAndBelongsToMonday() {
        assertEquals(d("2026-11-02"), at("2026-10-30T18:00:00").tradeDate());
        var sat = at("2026-10-31T05:59:59");
        assertEquals(TradingSession.NIGHT, sat.session());
        assertEquals(d("2026-11-02"), sat.tradeDate());
        assertTrue(cal.isOpen(kst("2026-10-31T05:59:59")));
        assertFalse(cal.isOpen(kst("2026-10-31T06:00:00")));
        assertFalse(cal.isOpen(kst("2026-10-31T18:30:00")), "no night session on Saturday evening");
        assertFalse(cal.isOpen(kst("2026-11-01T10:00:00")), "Sunday");
    }

    @Test
    void nightBeforeAHolidaySkipsToTheTradingDayAfterIt() {
        // 12/24(목) 야간장 → 12/25 휴장, 12/26·27 주말 → 12/28(월)
        assertEquals(d("2026-12-28"), at("2026-12-24T20:00:00").tradeDate());
        assertEquals(d("2026-12-28"), at("2026-12-25T03:00:00").tradeDate());
        assertTrue(cal.isOpen(kst("2026-12-25T03:00:00")), "the night that started on the 24th still runs");
    }

    @Test
    void holidayHasNoSessionsAndTheDayAfterIsNormal() {
        assertFalse(cal.isOpen(kst("2026-12-25T10:00:00")));
        assertFalse(cal.isOpen(kst("2026-12-25T20:00:00")));
        assertTrue(cal.isOpen(kst("2026-12-28T08:45:00")));
        var p = at("2026-12-28T09:00:00");
        assertEquals(TradingSession.DAY, p.session());
        assertEquals(d("2026-12-28"), p.tradeDate());
    }

    @Test
    void openDateRuleKeepsFridayNightOnFriday() {
        SessionCalendar openDate = TestCalendars.fromYaml(
                TestCalendars.FIXED.replace("night_trade_date: next_trading_day", "night_trade_date: open_date"));
        var p = openDate.classify(ZonedDateTime.parse("2026-10-31T02:00:00+09:00[Asia/Seoul]"));
        assertEquals(TradingSession.NIGHT, p.session());
        assertEquals(d("2026-10-30"), p.tradeDate());
    }

    // ------------------------------------------------------------------ 세션 안인가

    @Test
    void sessionBoundariesIncludeOpenAndExcludeClose() {
        assertFalse(cal.isOpen(kst("2026-10-28T08:44:59")));
        assertTrue(cal.isOpen(kst("2026-10-28T08:45:00")));
        assertTrue(cal.isOpen(kst("2026-10-28T15:44:59")));
        assertFalse(cal.isOpen(kst("2026-10-28T15:45:00")));
        assertFalse(cal.isOpen(kst("2026-10-28T17:59:59")));
        assertTrue(cal.isOpen(kst("2026-10-28T18:00:00")));
    }

    @Test
    void sessionAtTellsWhichWindow() {
        var w = cal.sessionAt(kst("2026-10-31T01:00:00")).orElseThrow();
        assertEquals(TradingSession.NIGHT, w.session());
        assertEquals(d("2026-10-30"), w.openDate());
        assertEquals(d("2026-11-02"), w.tradeDate());
        assertEquals(kst("2026-10-31T06:00:00"), w.close().toInstant());
    }

    @Test
    void connectsOneMinuteBeforeOpenAndDisconnectsAtClose() {
        Duration lead = Duration.ofMinutes(1);
        assertFalse(cal.shouldBeConnected(kst("2026-10-28T08:43:59"), lead));
        assertTrue(cal.shouldBeConnected(kst("2026-10-28T08:44:00"), lead));
        assertTrue(cal.shouldBeConnected(kst("2026-10-28T15:44:59"), lead));
        assertFalse(cal.shouldBeConnected(kst("2026-10-28T15:45:00"), lead));
        assertTrue(cal.shouldBeConnected(kst("2026-10-28T17:59:00"), lead));
        assertFalse(cal.shouldBeConnected(kst("2026-10-31T06:00:00"), lead));
    }

    @Test
    void nextOpenFindsTheNextSession() {
        var w = cal.nextOpen(kst("2026-10-30T15:50:00")).orElseThrow();
        assertEquals(TradingSession.NIGHT, w.session());
        assertEquals(kst("2026-10-30T18:00:00"), w.open().toInstant());

        var monday = cal.nextOpen(kst("2026-10-31T07:00:00")).orElseThrow();
        assertEquals(TradingSession.DAY, monday.session());
        assertEquals(kst("2026-11-02T08:45:00"), monday.open().toInstant());

        assertEquals(kst("2026-12-28T08:45:00"),
                cal.nextOpen(kst("2026-12-25T07:00:00")).orElseThrow().open().toInstant());
        // 지금 열려 있으면 그다음 세션
        assertEquals(kst("2026-10-28T18:00:00"),
                cal.nextOpen(kst("2026-10-28T10:00:00")).orElseThrow().open().toInstant());
    }

    // ------------------------------------------------------------------ 특수 개장

    @Test
    void overrideMovesTheDaySession() {
        assertFalse(cal.isOpen(kst("2026-11-19T09:00:00")));
        assertTrue(cal.isOpen(kst("2026-11-19T09:45:00")));
        assertTrue(cal.isOpen(kst("2026-11-19T16:44:59")));
        assertFalse(cal.isOpen(kst("2026-11-19T16:45:00")));
        assertTrue(cal.isOpen(kst("2026-11-19T18:00:00")), "night session is unchanged");
    }

    @Test
    void overrideCanCloseOneSession() {
        assertTrue(cal.isOpen(kst("2026-11-20T10:00:00")));
        assertFalse(cal.isOpen(kst("2026-11-20T20:00:00")));
        assertEquals(kst("2026-11-23T08:45:00"),
                cal.nextOpen(kst("2026-11-20T16:00:00")).orElseThrow().open().toInstant());
    }

    // ------------------------------------------------------------------ YAML

    @Test
    void repositoryCalendarFileLoads() {
        SessionCalendar real = TestCalendars.standard();
        assertTrue(real.isOpen(kst("2026-10-29T10:00:00")));
        assertFalse(real.isTradingDay(d("2026-12-25")));
    }

    @Test
    void badCalendarsFailAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> TestCalendars.fromYaml(TestCalendars.FIXED + "extra: 1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> TestCalendars.fromYaml(TestCalendars.FIXED.replace("\"08:45\"", "\"8.45\"")));
        assertThrows(IllegalArgumentException.class, () -> TestCalendars.fromYaml(
                TestCalendars.FIXED.replace("next_trading_day", "someday")));
        assertThrows(IllegalArgumentException.class, () -> TestCalendars.fromYaml(
                TestCalendars.FIXED.replace("2026-12-25", "2026-13-25")));
        assertThrows(IllegalArgumentException.class, () -> TestCalendars.fromYaml(
                TestCalendars.FIXED.replace("close: \"06:00\"", "close: \"20:00\"")));
    }
}
