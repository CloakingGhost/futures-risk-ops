package com.futuresriskops.collector.instrument;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 세션마다 어떤 선물 종목을 구독할지 정한다.
 *
 * <ul>
 *   <li>평소에는 근월물 하나.</li>
 *   <li>만기 전날(최종거래일 바로 앞 거래일)에 열린 세션부터 만기일 주간장까지는 근월물과 다음 결제월물 둘 다.
 *       새 코드가 실제로 체결을 받는지 만기 전에 확인하고, 바꾸는 순간의 빈틈을 없애려고 겹쳐 둔다.</li>
 *   <li>만기일 주간장이 끝나면 만기 종목을 뺀다. 만기일 야간장부터는 다음 결제월물만(가정).</li>
 * </ul>
 * 세션 시각이나 휴장일은 여기서 정하지 않고 세션 캘린더를 따른다.
 */
public final class NearMonthPlanner {

    /**
     * @param front     그 세션의 근월물 결제월
     * @param contracts 구독할 종목(근월물이 먼저)
     */
    public record Plan(YearMonth front, List<FuturesContract> contracts) {
    }

    private final SessionCalendar calendar;

    public NearMonthPlanner(SessionCalendar calendar) {
        this.calendar = calendar;
    }

    public Plan plan(LocalDate openDate, TradingSession session, FuturesContracts known) {
        YearMonth front = FuturesExpiry.frontMonth(openDate, session, calendar);
        List<FuturesContract> out = new ArrayList<>(2);
        out.add(known.forMonth(front));
        LocalDate last = FuturesExpiry.lastTradingDay(front, calendar);
        LocalDate overlapFrom = FuturesExpiry.previousTradingDay(last, calendar);
        if (!openDate.isBefore(overlapFrom)) {
            out.add(known.forMonth(FuturesExpiry.nextContractMonth(front)));
        }
        return new Plan(front, List.copyOf(out));
    }
}
