package com.futuresriskops.collector.store;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** tick_trade 적재. 같은 체결(종목·거래일·세션·누적거래량)은 ON CONFLICT DO NOTHING 으로 한 번만 남긴다. */
public class TickTradeRepository {

    private static final String INSERT_TRADE = """
            INSERT INTO tick_trade (instrument_id, traded_at, received_at, trade_date, session,
                                    price, qty, acc_volume, open_interest, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instrument_id, trade_date, session, acc_volume) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final Map<String, Long> instrumentIds = new ConcurrentHashMap<>();

    public TickTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 처음 보는 종목은 instrument 에 선물로 등록한다. 이름·만기·승수는 비워 둔다. */
    public long instrumentId(String symbol) {
        return instrumentIds.computeIfAbsent(symbol, s -> {
            jdbc.update("INSERT INTO instrument (symbol, product_type) VALUES (?, 'FUTURES') ON CONFLICT (symbol) DO NOTHING", s);
            return jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = ?", Long.class, s);
        });
    }

    /** 한 번의 배치로 넣고, 실제로 들어간 행 수를 돌려준다(중복은 빠진다). */
    public int insertBatch(List<TickTrade> trades) {
        if (trades.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate(INSERT_TRADE, trades, trades.size(), (ps, t) -> {
            ps.setLong(1, instrumentId(t.symbol()));
            ps.setTimestamp(2, Timestamp.from(t.tradedAt()));
            ps.setTimestamp(3, Timestamp.from(t.receivedAt()));
            ps.setDate(4, Date.valueOf(t.tradeDate()));
            ps.setString(5, t.session().name());
            ps.setBigDecimal(6, t.price());
            ps.setInt(7, t.qty());
            ps.setLong(8, t.accVolume());
            ps.setLong(9, t.openInterest());
            ps.setString(10, t.source().name());
        })[0];
        int inserted = 0;
        for (int c : counts) {
            inserted += Math.max(c, 0);
        }
        return inserted;
    }
}
