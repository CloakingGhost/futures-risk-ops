package com.futuresriskops.collector.store;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** tick_quote 적재. 호가에는 중복 판별 키가 없어 받은 대로 넣는다. 종목 id 는 체결과 같은 캐시를 쓴다. */
public class TickQuoteRepository {

    private static final String INSERT_QUOTE = """
            INSERT INTO tick_quote (instrument_id, quoted_at, received_at, trade_date, session,
                                    ask_prices, bid_prices, ask_qtys, bid_qtys, total_ask_qty, total_bid_qty, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TickTradeRepository instruments;

    public TickQuoteRepository(JdbcTemplate jdbc, TickTradeRepository instruments) {
        this.jdbc = jdbc;
        this.instruments = instruments;
    }

    public int insertBatch(List<TickQuote> quotes) {
        if (quotes.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(INSERT_QUOTE, quotes, quotes.size(), (ps, q) -> {
            var c = ps.getConnection();
            ps.setLong(1, instruments.instrumentId(q.symbol()));
            ps.setTimestamp(2, Timestamp.from(q.quotedAt()));
            ps.setTimestamp(3, Timestamp.from(q.receivedAt()));
            ps.setDate(4, Date.valueOf(q.tradeDate()));
            ps.setString(5, q.session().name());
            ps.setArray(6, c.createArrayOf("numeric", q.askPrices().toArray()));
            ps.setArray(7, c.createArrayOf("numeric", q.bidPrices().toArray()));
            ps.setArray(8, c.createArrayOf("integer", q.askQtys().toArray()));
            ps.setArray(9, c.createArrayOf("integer", q.bidQtys().toArray()));
            ps.setLong(10, q.totalAskQty());
            ps.setLong(11, q.totalBidQty());
            ps.setString(12, q.source().name());
        });
        return quotes.size();
    }
}
