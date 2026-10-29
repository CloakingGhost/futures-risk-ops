package com.futuresriskops.collector.instrument;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/**
 * 코스피200 선물 결제월과 만기(최종거래일) 계산.
 *
 * <ul>
 *   <li>결제월은 3·6·9·12월이다(분기). 매달 만기가 오는 것은 옵션이고, 옵션은 v0.1 범위에서 뺐다.</li>
 *   <li>최종거래일은 결제월 둘째 목요일이다. 그날이 휴장이면 앞의 거래일로 당긴다(휴장일은 세션 캘린더 YAML).</li>
 *   <li>만기일 야간장부터는 다음 결제월물만 거래된다고 본다(가정). 그래서 만기 종목은 만기일 주간장까지만 '들어 있다'.</li>
 * </ul>
 */
public final class FuturesExpiry {

    private FuturesExpiry() {
    }

    public static boolean isContractMonth(YearMonth m) {
        return m.getMonthValue() % 3 == 0;
    }

    /** {@code m} 이후(m 은 빼고) 첫 결제월. */
    public static YearMonth nextContractMonth(YearMonth m) {
        YearMonth n = m.plusMonths(1);
        while (!isContractMonth(n)) {
            n = n.plusMonths(1);
        }
        return n;
    }

    /** {@code m} 과 같거나 뒤의 첫 결제월. */
    public static YearMonth contractMonthOnOrAfter(YearMonth m) {
        return isContractMonth(m) ? m : nextContractMonth(m);
    }

    public static LocalDate secondThursday(YearMonth m) {
        return m.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY));
    }

    /** 결제월 {@code m} 종목의 최종거래일. 둘째 목요일이 휴장이면 앞의 거래일. */
    public static LocalDate lastTradingDay(YearMonth m, SessionCalendar calendar) {
        if (!isContractMonth(m)) {
            throw new IllegalArgumentException(m + " is not a KOSPI200 futures contract month (3, 6, 9, 12)");
        }
        return previousOrSameTradingDay(secondThursday(m), calendar);
    }

    public static LocalDate previousOrSameTradingDay(LocalDate d, SessionCalendar calendar) {
        LocalDate x = d;
        while (!calendar.isTradingDay(x)) {
            x = x.minusDays(1);
        }
        return x;
    }

    public static LocalDate previousTradingDay(LocalDate d, SessionCalendar calendar) {
        return previousOrSameTradingDay(d.minusDays(1), calendar);
    }

    /** {@code openDate} 에 열린 {@code session} 에서 결제월 {@code m} 종목이 거래되는지. 만기일은 주간장까지만. */
    public static boolean trades(YearMonth m, LocalDate openDate, TradingSession session, SessionCalendar calendar) {
        LocalDate last = lastTradingDay(m, calendar);
        return openDate.isBefore(last) || (openDate.equals(last) && session == TradingSession.DAY);
    }

    /** 그 세션의 근월물(아직 거래되는 가장 가까운 결제월). */
    public static YearMonth frontMonth(LocalDate openDate, TradingSession session, SessionCalendar calendar) {
        YearMonth m = contractMonthOnOrAfter(YearMonth.from(openDate));
        while (!trades(m, openDate, session, calendar)) {
            m = nextContractMonth(m);
        }
        return m;
    }
}
