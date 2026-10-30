package com.futuresriskops.risk.market;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** collector 가 쌓은 tick_trade 에서 최신 체결을 읽는다. 쓰지 않는다. */
@Repository
public class LatestTradeRepository {

    private static final RowMapper<LatestTrade> ROW = (rs, n) -> new LatestTrade(
            rs.getString("symbol"),
            rs.getTimestamp("traded_at").toInstant(),
            rs.getDate("trade_date").toLocalDate(),
            rs.getString("session"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("theoretical_price"),
            rs.getBigDecimal("market_basis"),
            rs.getBigDecimal("disparity_rate"),
            MarketSource.valueOf(rs.getString("source")));

    private final JdbcTemplate jdbc;

    public LatestTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LatestTrade> latest(String symbol, MarketSource source) {
        List<LatestTrade> rows = jdbc.query("""
                SELECT i.symbol, t.traded_at, t.trade_date, t.session, t.price,
                       t.theoretical_price, t.market_basis, t.disparity_rate, t.source
                FROM tick_trade t JOIN instrument i ON i.id = t.instrument_id
                WHERE i.symbol = ? AND t.source = ?
                ORDER BY t.traded_at DESC, t.acc_volume DESC
                LIMIT 1
                """, ROW, symbol, source.name());
        return rows.stream().findFirst();
    }

    /**
     * 종목을 정하지 않았을 때 쓸 종목. 종목 마스터의 최근월물(month_class = 1)이 있으면 그것을, 없으면(시뮬레이터)
     * 그 출처에서 가장 최근에 체결된 종목을 쓴다.
     */
    public Optional<String> defaultSymbol(MarketSource source) {
        List<String> nearMonth = jdbc.queryForList("""
                SELECT symbol FROM instrument
                WHERE product_type = 'FUTURES' AND month_class = 1
                ORDER BY master_updated_at DESC NULLS LAST
                LIMIT 1
                """, String.class);
        if (!nearMonth.isEmpty()) {
            return Optional.of(nearMonth.get(0));
        }
        List<String> recent = jdbc.queryForList("""
                SELECT i.symbol FROM tick_trade t JOIN instrument i ON i.id = t.instrument_id
                WHERE t.source = ?
                ORDER BY t.traded_at DESC
                LIMIT 1
                """, String.class, source.name());
        return recent.stream().findFirst();
    }
}
