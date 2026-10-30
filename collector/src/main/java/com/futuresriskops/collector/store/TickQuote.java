package com.futuresriskops.collector.store;

import com.futuresriskops.collector.kis.H0ifasp0Quote;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** tick_quote 한 행(5단계 호가). */
public record TickQuote(String symbol, Instant quotedAt, Instant receivedAt, LocalDate tradeDate,
                        TradingSession session, List<BigDecimal> askPrices, List<BigDecimal> bidPrices,
                        List<Integer> askQtys, List<Integer> bidQtys, long totalAskQty, long totalBidQty,
                        TickTrade.Source source) {

    /** 호가 일시·거래일·세션은 체결과 같은 방법(세션 캘린더)으로 정한다. */
    public static TickQuote of(H0ifasp0Quote q, Instant receivedAt, TickTrade.Source source, SessionCalendar calendar) {
        SessionCalendar.Placement p = calendar.place(q.bsopHour(), receivedAt);
        return new TickQuote(q.symbol(), p.tradedAt().toInstant(), receivedAt, p.tradeDate(), p.session(),
                q.askPrices(), q.bidPrices(), q.askQtys(), q.bidQtys(), q.totalAskQty(), q.totalBidQty(), source);
    }
}
