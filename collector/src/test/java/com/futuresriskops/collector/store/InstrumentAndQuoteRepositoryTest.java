package com.futuresriskops.collector.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.collector.instrument.FuturesContract;
import com.futuresriskops.collector.instrument.InstrumentRepository;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Flyway V5: instrument 마스터 Upsert 와 tick_quote 적재. Docker 가 없으면 건너뛴다. 값은 합성이다. */
@Testcontainers(disabledWithoutDocker = true)
class InstrumentAndQuoteRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private JdbcTemplate jdbc;
    private InstrumentRepository instruments;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.migrated(PG);
        jdbc.update("DELETE FROM tick_quote");
        jdbc.update("DELETE FROM tick_trade");
        jdbc.update("DELETE FROM instrument");
        instruments = new InstrumentRepository(jdbc, TestCalendars.fixed());
    }

    @Test
    void masterUpsertInsertsThenUpdatesBySymbolWithTheComputedExpiry() {
        Instant first = Instant.parse("2026-10-28T22:30:00Z");
        instruments.upsertFutures(List.of(
                new FuturesContract("A01612", "KR4A016C0004", "F 202612", YearMonth.of(2026, 12), 1),
                new FuturesContract("A01703", "KR4A01730006", "F 202703", YearMonth.of(2027, 3), 2)), first);
        // 다음 날 마스터에서 월물구분이 바뀌었다고 하자(합성)
        instruments.upsertFutures(List.of(
                new FuturesContract("A01612", "KR4A016C0004", "F 202612", YearMonth.of(2026, 12), 2)),
                first.plusSeconds(86_400));
        Map<String, Object> dec = jdbc.queryForMap(
                "SELECT product_type, underlying, expiry_date, contract_month, month_class FROM instrument WHERE symbol = 'A01612'");
        assertEquals("FUTURES", dec.get("product_type"));
        assertEquals("KOSPI200", dec.get("underlying"));
        assertEquals(java.sql.Date.valueOf("2026-12-10"), dec.get("expiry_date"));
        assertEquals("202612", dec.get("contract_month"));
        assertEquals(2, ((Number) dec.get("month_class")).intValue());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM instrument", Integer.class));
        assertEquals(List.of("A01612", "A01703"),
                instruments.loadFutures().stream().map(FuturesContract::symbol).toList());
    }

    @Test
    void anInstrumentFirstSeenInTradesIsCompletedByTheMaster() {
        TickTradeRepository trades = new TickTradeRepository(jdbc);
        long id = trades.instrumentId("A01612");
        instruments.upsertFutures(List.of(
                new FuturesContract("A01612", "KR4A016C0004", "F 202612", YearMonth.of(2026, 12), 1)), Instant.now());
        assertEquals(id, jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'A01612'", Long.class));
        assertEquals("202612", jdbc.queryForObject("SELECT contract_month FROM instrument WHERE id = ?", String.class, id));
    }

    @Test
    void quotesAreStoredAsFiveLevelArrays() {
        TickQuoteRepository quotes = new TickQuoteRepository(jdbc, new TickTradeRepository(jdbc));
        Instant at = Instant.parse("2026-10-29T00:00:01Z");
        List<BigDecimal> asks = List.of(new BigDecimal("400.05"), new BigDecimal("400.10"), new BigDecimal("400.15"),
                new BigDecimal("400.20"), new BigDecimal("400.25"));
        List<BigDecimal> bids = List.of(new BigDecimal("400.00"), new BigDecimal("399.95"), new BigDecimal("399.90"),
                new BigDecimal("399.85"), new BigDecimal("399.80"));
        TickQuote q = new TickQuote("A01612", at, at, LocalDate.of(2026, 10, 29), TradingSession.DAY, asks, bids,
                List.of(10, 17, 24, 31, 38), List.of(12, 19, 26, 33, 40), 900, 950, TickTrade.Source.SIMULATOR);
        assertEquals(2, quotes.insertBatch(List.of(q, q)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM tick_quote", Integer.class));
        assertEquals(new BigDecimal("400.2500"),
                jdbc.queryForObject("SELECT ask_prices[5] FROM tick_quote LIMIT 1", BigDecimal.class));
        assertEquals(40, jdbc.queryForObject("SELECT bid_qtys[5] FROM tick_quote LIMIT 1", Integer.class));
    }
}
