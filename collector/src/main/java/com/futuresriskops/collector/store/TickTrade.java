package com.futuresriskops.collector.store;

import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import com.futuresriskops.collector.session.TemporaryTradeDateRule;
import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** tick_trade 한 행. */
public record TickTrade(String symbol, Instant tradedAt, Instant receivedAt, LocalDate tradeDate,
                        TradingSession session, BigDecimal price, int qty, long accVolume, long openInterest,
                        Source source) {

    /** tick_trade.source 허용값. */
    public enum Source {
        KIS,
        SIMULATOR,
        REPLAY
    }

    public static TickTrade of(H0ifcnt0Trade t, Instant receivedAt, Source source) {
        TemporaryTradeDateRule.Placement p = TemporaryTradeDateRule.place(t.bsopHour(), receivedAt);
        return new TickTrade(t.symbol(), p.tradedAt().toInstant(), receivedAt, p.tradeDate(), p.session(),
                t.price(), t.qty(), t.accVolume(), t.openInterest(), source);
    }
}
