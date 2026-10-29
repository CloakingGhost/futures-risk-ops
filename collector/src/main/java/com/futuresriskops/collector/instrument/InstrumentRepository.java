package com.futuresriskops.collector.instrument;

import com.futuresriskops.collector.session.SessionCalendar;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** instrument 의 선물 종목을 마스터 값으로 Upsert 하고, 마스터를 못 받았을 때 DB 에 있던 값을 읽는다. */
public class InstrumentRepository implements InstrumentStore {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private static final String UPSERT = """
            INSERT INTO instrument (symbol, name, product_type, underlying, expiry_date, standard_code,
                                    contract_month, month_class, master_updated_at)
            VALUES (?, ?, 'FUTURES', 'KOSPI200', ?, ?, ?, ?, ?)
            ON CONFLICT (symbol) DO UPDATE SET
                name = EXCLUDED.name,
                product_type = EXCLUDED.product_type,
                underlying = EXCLUDED.underlying,
                expiry_date = EXCLUDED.expiry_date,
                standard_code = EXCLUDED.standard_code,
                contract_month = EXCLUDED.contract_month,
                month_class = EXCLUDED.month_class,
                master_updated_at = EXCLUDED.master_updated_at
            """;

    private final JdbcTemplate jdbc;
    private final SessionCalendar calendar;

    public InstrumentRepository(JdbcTemplate jdbc, SessionCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Override
    public int upsertFutures(List<FuturesContract> contracts, Instant at) {
        if (contracts.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(UPSERT, contracts, contracts.size(), (ps, c) -> {
            ps.setString(1, c.symbol());
            ps.setString(2, c.name());
            ps.setDate(3, Date.valueOf(FuturesExpiry.lastTradingDay(c.contractMonth(), calendar)));
            ps.setString(4, c.standardCode());
            ps.setString(5, c.contractMonth().format(YYYYMM));
            ps.setInt(6, c.monthClass());
            ps.setTimestamp(7, Timestamp.from(at));
        });
        return contracts.size();
    }

    @Override
    public List<FuturesContract> loadFutures() {
        return jdbc.query("""
                SELECT symbol, standard_code, name, contract_month, month_class
                  FROM instrument
                 WHERE product_type = 'FUTURES' AND contract_month IS NOT NULL AND symbol LIKE 'A01%'
                 ORDER BY contract_month
                """, (rs, i) -> new FuturesContract(rs.getString(1), rs.getString(2), rs.getString(3),
                YearMonth.parse(rs.getString(4), YYYYMM), rs.getInt(5)));
    }
}
