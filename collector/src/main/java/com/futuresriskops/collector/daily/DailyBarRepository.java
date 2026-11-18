package com.futuresriskops.collector.daily;

import com.futuresriskops.collector.rest.DailyBar;
import com.futuresriskops.collector.store.TickTrade;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.ToLongFunction;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * daily_bar 적재(Flyway V7). 같은 (종목, 영업일, 시장, 출처)를 다시 받으면 KIS 가 준 새 값으로 덮어쓰고
 * fetched_at 을 갱신한다(ON CONFLICT DO UPDATE). 그래서 같은 구간을 몇 번 받아도 행 수와 값이 같다.
 */
public class DailyBarRepository {

    private static final String UPSERT = """
            INSERT INTO daily_bar (instrument_id, trade_date, market, source, open_price, high_price, low_price,
                                   close_price, acc_volume, acc_trade_value, mod_yn, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instrument_id, trade_date, market, source) DO UPDATE SET
                open_price      = EXCLUDED.open_price,
                high_price      = EXCLUDED.high_price,
                low_price       = EXCLUDED.low_price,
                close_price     = EXCLUDED.close_price,
                acc_volume      = EXCLUDED.acc_volume,
                acc_trade_value = EXCLUDED.acc_trade_value,
                mod_yn          = EXCLUDED.mod_yn,
                fetched_at      = EXCLUDED.fetched_at
            """;

    private final JdbcTemplate jdbc;
    private final ToLongFunction<String> instrumentId;

    /** @param instrumentId 종목 코드로 instrument.id 를 얻는다(처음 보는 종목은 등록한다. 보통 {@code TickTradeRepository::instrumentId}) */
    public DailyBarRepository(JdbcTemplate jdbc, ToLongFunction<String> instrumentId) {
        this.jdbc = jdbc;
        this.instrumentId = instrumentId;
    }

    /** @return 쓴 행 수(새로 넣은 것과 덮어쓴 것 모두) */
    public int upsert(String symbol, String market, TickTrade.Source source, List<DailyBar> bars, Instant fetchedAt) {
        if (bars.isEmpty()) {
            return 0;
        }
        long id = instrumentId.applyAsLong(symbol);
        jdbc.batchUpdate(UPSERT, bars, bars.size(), (ps, b) -> {
            ps.setLong(1, id);
            ps.setDate(2, Date.valueOf(b.tradeDate()));
            ps.setString(3, market);
            ps.setString(4, source.name());
            ps.setBigDecimal(5, b.open());
            ps.setBigDecimal(6, b.high());
            ps.setBigDecimal(7, b.low());
            ps.setBigDecimal(8, b.close());
            ps.setLong(9, b.volume());
            ps.setBigDecimal(10, b.tradeValue());
            ps.setString(11, b.modYn() == null || b.modYn().isBlank() ? null : b.modYn());
            ps.setTimestamp(12, Timestamp.from(fetchedAt));
        });
        return bars.size();
    }
}
