package com.futuresriskops.collector.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 12월물 만기(2026-12-10 목) 앞뒤로 구독 종목이 어떻게 바뀌는지 본다. */
class NearMonthPlannerTest {

    private static final SessionCalendar CAL = TestCalendars.fixed();
    private final NearMonthPlanner planner = new NearMonthPlanner(CAL);

    private List<String> symbols(String openDate, TradingSession s, FuturesContracts known) {
        return planner.plan(LocalDate.parse(openDate), s, known).contracts().stream().map(FuturesContract::symbol)
                .toList();
    }

    private List<String> symbols(String openDate, TradingSession s) {
        return symbols(openDate, s, FuturesContracts.empty());
    }

    @Test
    void normallyOnlyTheFrontMonth() {
        assertEquals(List.of("A01612"), symbols("2026-10-29", TradingSession.DAY));
        assertEquals(List.of("A01612"), symbols("2026-12-08", TradingSession.NIGHT));
    }

    @Test
    void fromTheSessionsOpenedTheDayBeforeExpiryBothMonthsAreSubscribed() {
        assertEquals(List.of("A01612", "A01703"), symbols("2026-12-09", TradingSession.DAY));
        assertEquals(List.of("A01612", "A01703"), symbols("2026-12-09", TradingSession.NIGHT));
        assertEquals(List.of("A01612", "A01703"), symbols("2026-12-10", TradingSession.DAY));
    }

    @Test
    void afterTheExpiryDayDaySessionOnlyTheNextMonthRemains() {
        assertEquals(List.of("A01703"), symbols("2026-12-10", TradingSession.NIGHT));
        assertEquals(List.of("A01703"), symbols("2026-12-11", TradingSession.DAY));
        assertEquals(YearMonth.of(2027, 3),
                planner.plan(LocalDate.parse("2026-12-10"), TradingSession.NIGHT, FuturesContracts.empty()).front());
    }

    @Test
    void theDayBeforeExpiryIsTheTradingDayBeforeNotTheCalendarDay() {
        // 2027-06-10(목) 만기. 테스트 캘린더에서 6/9(수) 휴장이면 겹침은 6/8(화)부터
        SessionCalendar cal = TestCalendars.fromYaml(TestCalendars.FIXED.replace(
                "  - { date: 2026-12-25, name: test holiday }",
                "  - { date: 2026-12-25, name: test holiday }\n  - { date: 2027-06-09, name: test holiday }"));
        NearMonthPlanner p = new NearMonthPlanner(cal);
        assertEquals(1, p.plan(LocalDate.parse("2027-06-07"), TradingSession.DAY, FuturesContracts.empty())
                .contracts().size());
        assertEquals(2, p.plan(LocalDate.parse("2027-06-08"), TradingSession.DAY, FuturesContracts.empty())
                .contracts().size());
    }

    @Test
    void masterCodesWinOverTheRule() {
        // 합성: 규칙과 다른 코드가 마스터에 있으면 마스터 코드를 쓴다
        FuturesContracts known = new FuturesContracts(List.of(
                new FuturesContract("X01612", "KR4X", "F 202612", YearMonth.of(2026, 12), 1)), "master");
        assertEquals(List.of("X01612"), symbols("2026-10-29", TradingSession.DAY, known));
        assertEquals(1, known.ruleMismatches().size());
        // 마스터에 없는 다음 결제월은 규칙으로 만든다
        assertEquals(List.of("X01612", "A01703"), symbols("2026-12-09", TradingSession.DAY, known));
    }
}
