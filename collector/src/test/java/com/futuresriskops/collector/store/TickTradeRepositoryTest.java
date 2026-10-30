package com.futuresriskops.collector.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL 에서 V1~V5 마이그레이션과 중복 제거를 확인한다. Docker 가 없으면 건너뛴다. */
@Testcontainers(disabledWithoutDocker = true)
class TickTradeRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private JdbcTemplate jdbc;
    private TickTradeRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.migrated(PG);
        jdbc.update("DELETE FROM tick_trade");
        repo = new TickTradeRepository(jdbc);
    }

    private static TickTrade trade(long accVolume, TradingSession session, TickTrade.Source source) {
        Instant at = Instant.parse("2026-10-27T00:00:01Z");
        return new TickTrade("A01612", at, at, LocalDate.of(2026, 10, 27), session, new BigDecimal("400.05"), 2,
                accVolume, 50_001, source);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM tick_trade", Integer.class);
    }

    @Test
    void migrationsCreateTheV2Columns() {
        List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'tick_trade'", String.class);
        assertTrue(cols.containsAll(List.of("trade_date", "session")), cols.toString());
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    }

    @Test
    void sameTradeTwiceLeavesOneRow() {
        List<TickTrade> batch = List.of(trade(10, TradingSession.DAY, TickTrade.Source.SIMULATOR),
                trade(12, TradingSession.DAY, TickTrade.Source.SIMULATOR));
        assertEquals(2, repo.insertBatch(batch));
        assertEquals(0, repo.insertBatch(batch));
        assertEquals(2, rows());
    }

    @Test
    void sameAccVolumeInAnotherSessionIsADifferentTrade() {
        repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.SIMULATOR)));
        assertEquals(1, repo.insertBatch(List.of(trade(10, TradingSession.NIGHT, TickTrade.Source.SIMULATOR))));
        assertEquals(2, rows());
    }

    @Test
    void sameAccVolumeFromAnotherSourceIsKept() {
        // V3: 시뮬레이터 행이 같은 종목·같은 날의 KIS·리플레이 체결을 막지 않는다
        repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.SIMULATOR)));
        assertEquals(1, repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.KIS))));
        assertEquals(1, repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.REPLAY))));
        assertEquals(1, repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.KIS_PAPER))),
                "V4: paper trading is stored apart from the real account");
        assertEquals(0, repo.insertBatch(List.of(trade(10, TradingSession.DAY, TickTrade.Source.KIS))),
                "the same source still deduplicates");
        assertEquals(4, rows());
    }

    @Test
    void replaySourceIsAllowedAndUnknownSessionIsRejected() {
        assertEquals(1, repo.insertBatch(List.of(trade(20, TradingSession.DAY, TickTrade.Source.REPLAY))));
        long id = repo.instrumentId("A01612");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO tick_trade (instrument_id, traded_at, trade_date, session, price, qty, acc_volume, source)"
                        + " VALUES (?, now(), current_date, 'NOON', 1, 1, 99, 'KIS')", id));
    }

    @Test
    void unknownSymbolsAreRegisteredOnce() {
        long first = repo.instrumentId("A01703");
        long again = new TickTradeRepository(jdbc).instrumentId("A01703");
        assertEquals(first, again);
        assertEquals("FUTURES", jdbc.queryForObject(
                "SELECT product_type FROM instrument WHERE symbol = 'A01703'", String.class));
    }
}
