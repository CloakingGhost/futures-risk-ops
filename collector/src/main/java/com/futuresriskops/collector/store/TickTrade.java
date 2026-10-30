package com.futuresriskops.collector.store;

import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * tick_trade 한 행. {@code theoreticalPrice}, {@code marketBasis}, {@code disparityRate} 는 KIS 가 체결 메시지에 담아 보낸 값이고
 * 없으면 null 이다(Flyway V6).
 */
public record TickTrade(String symbol, Instant tradedAt, Instant receivedAt, LocalDate tradeDate,
                        TradingSession session, BigDecimal price, int qty, long accVolume, long openInterest,
                        BigDecimal theoreticalPrice, BigDecimal marketBasis, BigDecimal disparityRate,
                        Source source) {

    /** tick_trade.source 허용값. */
    public enum Source {
        /** KIS 실전 */
        KIS,
        /** KIS 모의투자(Flyway V4) */
        KIS_PAPER,
        SIMULATOR,
        REPLAY
    }

    /** 체결 일시·거래일·세션은 세션 캘린더가 정한다. */
    public static TickTrade of(H0ifcnt0Trade t, Instant receivedAt, Source source, SessionCalendar calendar) {
        SessionCalendar.Placement p = calendar.place(t.bsopHour(), receivedAt);
        return new TickTrade(t.symbol(), p.tradedAt().toInstant(), receivedAt, p.tradeDate(), p.session(),
                t.price(), t.qty(), t.accVolume(), t.openInterest(), t.theoreticalPrice(), t.marketBasis(),
                t.disparityRate(), source);
    }
}
