package com.futuresriskops.collector.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TemporaryTradeDateRuleTest {

    private static Instant kst(String dateTime) {
        return ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]").toInstant();
    }

    @Test
    void dayTradeUsesTheReceivedDate() {
        var p = TemporaryTradeDateRule.place(LocalTime.of(9, 0, 1), kst("2026-10-27T09:00:02"));
        assertEquals(LocalDate.of(2026, 10, 27), p.tradeDate());
        assertEquals(TradingSession.DAY, p.session());
        assertEquals(kst("2026-10-27T09:00:01"), p.tradedAt().toInstant());
    }

    @Test
    void nightTradeBeforeMidnightIsNightSession() {
        var p = TemporaryTradeDateRule.place(LocalTime.of(18, 0), kst("2026-10-27T18:00:00"));
        assertEquals(TradingSession.NIGHT, p.session());
        assertEquals(LocalDate.of(2026, 10, 27), p.tradeDate());
    }

    @Test
    void tradeJustBeforeMidnightReceivedAfterMidnightKeepsItsOwnDate() {
        var p = TemporaryTradeDateRule.place(LocalTime.of(23, 59, 59), kst("2026-10-28T00:00:01"));
        assertEquals(kst("2026-10-27T23:59:59"), p.tradedAt().toInstant());
        assertEquals(LocalDate.of(2026, 10, 27), p.tradeDate());
        assertEquals(TradingSession.NIGHT, p.session());
    }

    @Test
    void earlyMorningIsNightUntilSix() {
        assertEquals(TradingSession.NIGHT,
                TemporaryTradeDateRule.place(LocalTime.of(5, 59, 59), kst("2026-10-31T05:59:59")).session());
        assertEquals(TradingSession.DAY,
                TemporaryTradeDateRule.place(LocalTime.of(8, 45), kst("2026-10-31T08:45:00")).session());
    }
}
