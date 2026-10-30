package com.futuresriskops.risk.market;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 테스트에서 쓰는 임시 PostgreSQL 16. collector 가 운영에서 하는 것처럼 db 모듈 마이그레이션을 적용한다(테스트에서만). */
public final class PostgresTestSupport {

    private PostgresTestSupport() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>("postgres:16");
    }

    public static DriverManagerDataSource migrated(PostgreSQLContainer<?> pg) {
        DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }

    /** 합성 체결 한 건. 값은 손으로 만든 것이다. */
    public static void trade(JdbcTemplate jdbc, String symbol, String tradedAt, String tradeDate, String session,
                             String price, long accVolume, long openInterest, String basis, String source) {
        jdbc.update("INSERT INTO instrument (symbol, product_type) VALUES (?, 'FUTURES') ON CONFLICT (symbol) DO NOTHING",
                symbol);
        jdbc.update("""
                INSERT INTO tick_trade (instrument_id, traded_at, received_at, trade_date, session, price, qty,
                                        acc_volume, open_interest, theoretical_price, market_basis, disparity_rate, source)
                SELECT id, ?::timestamptz, ?::timestamptz, ?::date, ?, ?::numeric, 1, ?, ?,
                       CASE WHEN ?::numeric IS NULL THEN NULL ELSE ?::numeric - 0.10 END, ?::numeric,
                       CASE WHEN ?::numeric IS NULL THEN NULL ELSE 0.0250 END, ?
                FROM instrument WHERE symbol = ?
                """, tradedAt, tradedAt, tradeDate, session, price, accVolume, openInterest,
                basis, price, basis, basis, source, symbol);
    }
}
