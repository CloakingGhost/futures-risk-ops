package com.futuresriskops.risk.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL(V1~V6)에서 risk 의 읽기 쿼리를 확인한다. 데이터는 합성값이다. Docker 가 없으면 건너뛴다. */
@Testcontainers(disabledWithoutDocker = true)
class MarketRepositoriesTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private JdbcTemplate jdbc;
    private LatestTradeRepository latest;
    private OpenInterestHistory oi;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(PostgresTestSupport.migrated(PG));
        jdbc.update("DELETE FROM tick_trade");
        jdbc.update("DELETE FROM tick_quote");
        jdbc.update("DELETE FROM instrument");
        latest = new LatestTradeRepository(jdbc);
        oi = new OpenInterestHistory(jdbc);
    }

    private void trade(String symbol, String at, String date, String session, String price, long acc, long openInterest,
                       String basis, String source) {
        PostgresTestSupport.trade(jdbc, symbol, at, date, session, price, acc, openInterest, basis, source);
    }

    @Test
    void latestTradeCarriesTheKisBasisAndTheImpliedIndex() {
        trade("A01612", "2026-10-30T00:00:01Z", "2026-10-30", "DAY", "412.50", 10, 250_000, "0.40", "SIMULATOR");
        trade("A01612", "2026-10-30T00:00:02Z", "2026-10-30", "DAY", "412.60", 11, 250_001, "0.45", "SIMULATOR");

        LatestTrade t = latest.latest("A01612", MarketSource.SIMULATOR).orElseThrow();
        assertEquals(0, new BigDecimal("412.60").compareTo(t.price()));
        assertEquals(0, new BigDecimal("0.45").compareTo(t.marketBasis()));
        assertEquals(0, new BigDecimal("412.15").compareTo(t.impliedIndex()));
        assertEquals("DAY", t.session());
    }

    @Test
    void rowsBeforeV6HaveNoBasisAndNoImpliedIndex() {
        trade("A01612", "2026-10-29T00:00:01Z", "2026-10-29", "DAY", "411.00", 10, 250_000, null, "KIS_PAPER");
        LatestTrade t = latest.latest("A01612", MarketSource.KIS_PAPER).orElseThrow();
        assertNull(t.marketBasis());
        assertNull(t.impliedIndex());
    }

    @Test
    void sourcesAreNotMixed() {
        trade("A01612", "2026-10-30T00:00:01Z", "2026-10-30", "DAY", "412.50", 10, 250_000, "0.40", "KIS_PAPER");
        trade("A01612", "2026-10-30T00:00:09Z", "2026-10-30", "DAY", "999.00", 99, 1, "0.40", "SIMULATOR");
        assertEquals(0, new BigDecimal("412.50").compareTo(
                latest.latest("A01612", MarketSource.KIS_PAPER).orElseThrow().price()));
        assertTrue(latest.latest("A01612", MarketSource.KIS).isEmpty());
    }

    @Test
    void defaultSymbolIsTheNearMonthFromTheMasterOrElseTheLastTraded() {
        trade("A01612", "2026-10-30T00:00:01Z", "2026-10-30", "DAY", "412.50", 10, 250_000, "0.40", "SIMULATOR");
        trade("A01703", "2026-10-30T00:00:05Z", "2026-10-30", "DAY", "414.00", 3, 1_000, "1.90", "SIMULATOR");
        assertEquals("A01703", latest.defaultSymbol(MarketSource.SIMULATOR).orElseThrow());

        jdbc.update("UPDATE instrument SET month_class = 1, master_updated_at = now() WHERE symbol = 'A01612'");
        assertEquals("A01612", latest.defaultSymbol(MarketSource.SIMULATOR).orElseThrow());
    }

    @Test
    void dailyOpenInterestUsesTheLastTradeOfEachTradingDay() {
        // 10/30 거래일: 10/29 야간(NIGHT) + 10/30 주간. 마지막 체결은 주간장 끝
        trade("A01612", "2026-10-29T09:00:00Z", "2026-10-30", "NIGHT", "411.00", 5, 249_000, "0.30", "SIMULATOR");
        trade("A01612", "2026-10-30T06:44:00Z", "2026-10-30", "DAY", "412.00", 50, 250_300, "0.30", "SIMULATOR");
        trade("A01612", "2026-10-29T06:44:00Z", "2026-10-29", "DAY", "410.00", 40, 251_000, "0.30", "SIMULATOR");
        trade("A01612", "2026-10-28T06:44:00Z", "2026-10-28", "DAY", "409.00", 30, 249_500, "0.30", "SIMULATOR");

        List<DailyOpenInterest> days = oi.daily("A01612", MarketSource.SIMULATOR, 2);
        assertEquals(2, days.size());
        assertEquals(LocalDate.parse("2026-10-30"), days.get(0).tradeDate());
        assertEquals(250_300, days.get(0).openInterest());
        assertEquals(-700L, days.get(0).change());
        assertEquals(1_500L, days.get(1).change());
    }
}
