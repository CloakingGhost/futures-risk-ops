package com.futuresriskops.risk.market;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 거래일별 미결제약정과 전 거래일 대비 증감.
 *
 * <p>지금은 tick_trade 에서 거래일마다 마지막 체결의 open_interest 를 읽는다. Day 7 에 daily_bar 가 생기면
 * {@link #daily} 안의 쿼리만 바꾸고 API 는 그대로 둔다(계획 V2). 야간장은 다음 거래일에 속하므로, 거래일의 마지막 체결은
 * 보통 그날 주간장 끝이다.
 */
@Repository
public class OpenInterestHistory {

    private final JdbcTemplate jdbc;

    public OpenInterestHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 최근 {@code days} 거래일, 최신이 먼저. 증감 계산에 쓰려고 하루를 더 읽는다. */
    public List<DailyOpenInterest> daily(String symbol, MarketSource source, int days) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be at least 1");
        }
        List<DailyOpenInterest> raw = jdbc.query("""
                SELECT DISTINCT ON (t.trade_date) t.trade_date, t.open_interest, t.traded_at
                FROM tick_trade t JOIN instrument i ON i.id = t.instrument_id
                WHERE i.symbol = ? AND t.source = ? AND t.open_interest IS NOT NULL
                ORDER BY t.trade_date DESC, t.traded_at DESC, t.acc_volume DESC
                LIMIT ?
                """, (rs, n) -> new DailyOpenInterest(rs.getDate("trade_date").toLocalDate(),
                        rs.getLong("open_interest"), null, rs.getTimestamp("traded_at").toInstant()),
                symbol, source.name(), days + 1);
        return withChanges(raw, days);
    }

    /** 최신이 먼저인 목록에 전 거래일 대비 증감을 붙이고 {@code days} 개로 자른다. */
    static List<DailyOpenInterest> withChanges(List<DailyOpenInterest> newestFirst, int days) {
        List<DailyOpenInterest> out = new ArrayList<>();
        for (int i = 0; i < Math.min(days, newestFirst.size()); i++) {
            DailyOpenInterest d = newestFirst.get(i);
            Long change = i + 1 < newestFirst.size() ? d.openInterest() - newestFirst.get(i + 1).openInterest() : null;
            out.add(new DailyOpenInterest(d.tradeDate(), d.openInterest(), change, d.lastTradeAt()));
        }
        return out;
    }
}
