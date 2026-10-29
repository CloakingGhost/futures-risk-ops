package com.futuresriskops.collector.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * 만기 규칙 15개. 선물 결제월은 3·6·9·12월 둘째 목요일이고, 휴장이면 앞의 거래일로 당긴다.
 * 휴장일이 필요한 경우는 테스트용 캘린더를 따로 만든다(실제 휴장일 공지가 아니다).
 */
class FuturesExpiryTest {

    private static final SessionCalendar CAL = TestCalendars.fixed();

    /** 2027-03-11(둘째 목요일)을 휴장으로 둔 테스트 캘린더. */
    private static SessionCalendar withHolidays(String... dates) {
        StringBuilder yaml = new StringBuilder("""
                timezone: Asia/Seoul
                regular:
                  day:   { open: "08:45", close: "15:45" }
                  night: { open: "18:00", close: "06:00" }
                night_trade_date: next_trading_day
                holidays:
                """);
        for (String d : dates) {
            yaml.append("  - { date: ").append(d).append(", name: test holiday }\n");
        }
        return TestCalendars.fromYaml(yaml.toString());
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void december2026ExpiresOnDecember10() {
        assertEquals(d("2026-12-10"), FuturesExpiry.lastTradingDay(YearMonth.of(2026, 12), CAL));
    }

    @Test
    void the2027ContractsExpireOnTheirSecondThursdays() {
        assertEquals(d("2027-03-11"), FuturesExpiry.lastTradingDay(YearMonth.of(2027, 3), CAL));
        assertEquals(d("2027-06-10"), FuturesExpiry.lastTradingDay(YearMonth.of(2027, 6), CAL));
        assertEquals(d("2027-09-09"), FuturesExpiry.lastTradingDay(YearMonth.of(2027, 9), CAL));
        assertEquals(d("2027-12-09"), FuturesExpiry.lastTradingDay(YearMonth.of(2027, 12), CAL));
    }

    @Test
    void whenTheFirstIsAThursdayTheSecondThursdayIsThe8th() {
        // 2028-06-01, 2029-03-01 은 목요일
        assertEquals(d("2028-06-08"), FuturesExpiry.secondThursday(YearMonth.of(2028, 6)));
        assertEquals(d("2029-03-08"), FuturesExpiry.secondThursday(YearMonth.of(2029, 3)));
    }

    @Test
    void whenTheFirstIsAFridayTheSecondThursdayIsThe14th() {
        // 2028-09-01 은 금요일
        assertEquals(d("2028-09-14"), FuturesExpiry.secondThursday(YearMonth.of(2028, 9)));
    }

    @Test
    void onlyQuarterMonthsAreFuturesContractMonths() {
        for (int m = 1; m <= 12; m++) {
            assertEquals(m % 3 == 0, FuturesExpiry.isContractMonth(YearMonth.of(2026, m)), "month " + m);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FuturesExpiry.lastTradingDay(YearMonth.of(2026, 11), CAL));
        assertTrue(e.getMessage().contains("3, 6, 9, 12"));
    }

    @Test
    void aHolidayOnTheSecondThursdayMovesExpiryToWednesday() {
        assertEquals(d("2027-03-10"), FuturesExpiry.lastTradingDay(YearMonth.of(2027, 3), withHolidays("2027-03-11")));
    }

    @Test
    void twoHolidaysInARowMoveExpiryBackTwoDays() {
        assertEquals(d("2027-03-09"),
                FuturesExpiry.lastTradingDay(YearMonth.of(2027, 3), withHolidays("2027-03-10", "2027-03-11")));
    }

    @Test
    void nextContractMonthSkipsToTheNextQuarter() {
        assertEquals(YearMonth.of(2027, 3), FuturesExpiry.nextContractMonth(YearMonth.of(2026, 12)));
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.nextContractMonth(YearMonth.of(2026, 11)));
        assertEquals(YearMonth.of(2027, 6), FuturesExpiry.nextContractMonth(YearMonth.of(2027, 3)));
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.contractMonthOnOrAfter(YearMonth.of(2026, 12)));
    }

    @Test
    void frontMonthToday() {
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.frontMonth(d("2026-10-29"), TradingSession.DAY, CAL));
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.frontMonth(d("2026-10-29"), TradingSession.NIGHT, CAL));
    }

    @Test
    void theExpiringContractIsStillFrontOnExpiryDayDaySession() {
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.frontMonth(d("2026-12-10"), TradingSession.DAY, CAL));
        assertTrue(FuturesExpiry.trades(YearMonth.of(2026, 12), d("2026-12-10"), TradingSession.DAY, CAL));
    }

    @Test
    void theNightSessionOfExpiryDayRollsToMarch() {
        assertEquals(YearMonth.of(2027, 3), FuturesExpiry.frontMonth(d("2026-12-10"), TradingSession.NIGHT, CAL));
        assertFalse(FuturesExpiry.trades(YearMonth.of(2026, 12), d("2026-12-10"), TradingSession.NIGHT, CAL));
    }

    @Test
    void theDayAfterExpiryTheFrontIsMarch() {
        assertEquals(YearMonth.of(2027, 3), FuturesExpiry.frontMonth(d("2026-12-11"), TradingSession.DAY, CAL));
    }

    @Test
    void aHolidayShiftedExpiryRollsAfterTheShiftedDay() {
        SessionCalendar cal = withHolidays("2027-03-11");
        assertEquals(YearMonth.of(2027, 3), FuturesExpiry.frontMonth(d("2027-03-10"), TradingSession.DAY, cal));
        assertEquals(YearMonth.of(2027, 6), FuturesExpiry.frontMonth(d("2027-03-10"), TradingSession.NIGHT, cal));
        assertEquals(YearMonth.of(2027, 6), FuturesExpiry.frontMonth(d("2027-03-12"), TradingSession.DAY, cal));
    }

    @Test
    void novemberOptionExpiryDoesNotRollFutures() {
        // 2026-11-12 는 11월물 옵션 만기(둘째 목요일)이지만 선물 결제월이 아니다
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.frontMonth(d("2026-11-12"), TradingSession.DAY, CAL));
        assertEquals(YearMonth.of(2026, 12), FuturesExpiry.frontMonth(d("2026-11-12"), TradingSession.NIGHT, CAL));
    }

    @Test
    void theCodeRuleMatchesTheMasterFileCodes() {
        // 2026-10-28 마스터 파일의 코스피200 선물 7종목과 같은 코드
        assertEquals("A01612", FuturesCode.forMonth(YearMonth.of(2026, 12)));
        assertEquals("A01703", FuturesCode.forMonth(YearMonth.of(2027, 3)));
        assertEquals("A01709", FuturesCode.forMonth(YearMonth.of(2027, 9)));
        assertEquals("A01812", FuturesCode.forMonth(YearMonth.of(2028, 12)));
    }
}
