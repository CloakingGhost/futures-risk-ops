package com.futuresriskops.risk.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 종목의 가장 최근 체결 한 건. 베이시스·이론가·괴리율은 KIS 가 체결 메시지에 담아 보낸 값이고(Flyway V6), 없으면 null 이다.
 *
 * @param session DAY 또는 NIGHT
 */
public record LatestTrade(String symbol, Instant tradedAt, LocalDate tradeDate, String session, BigDecimal price,
                          BigDecimal theoreticalPrice, BigDecimal marketBasis, BigDecimal disparityRate,
                          MarketSource source) {

    /** 코스피200 지수 = 선물가 - 시장 베이시스(가정: mrkt_basis 의 뜻, docs/kis-h0ifcnt0.md). 베이시스가 없으면 null. */
    public BigDecimal impliedIndex() {
        return marketBasis == null ? null : price.subtract(marketBasis);
    }
}
