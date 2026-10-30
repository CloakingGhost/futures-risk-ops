package com.futuresriskops.risk.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 증감 계산만 본다(DB 없음). DB 쿼리는 MarketRepositoriesTest. */
class OpenInterestHistoryTest {

    private static DailyOpenInterest day(String date, long oi) {
        return new DailyOpenInterest(LocalDate.parse(date), oi, null, Instant.parse(date + "T06:45:00Z"));
    }

    @Test
    void changeIsAgainstThePreviousTradingDayAndTheExtraDayIsDropped() {
        List<DailyOpenInterest> newestFirst = List.of(day("2026-10-30", 250_300), day("2026-10-29", 251_000),
                day("2026-10-28", 249_500));
        List<DailyOpenInterest> out = OpenInterestHistory.withChanges(newestFirst, 2);
        assertEquals(2, out.size());
        assertEquals(-700L, out.get(0).change());
        assertEquals(1_500L, out.get(1).change());
    }

    @Test
    void theOldestDayHasNoChangeWhenThereIsNothingBeforeIt() {
        List<DailyOpenInterest> out = OpenInterestHistory.withChanges(List.of(day("2026-10-30", 250_300)), 5);
        assertEquals(1, out.size());
        assertNull(out.get(0).change());
    }
}
