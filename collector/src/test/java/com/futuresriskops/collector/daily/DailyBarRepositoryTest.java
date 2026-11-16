package com.futuresriskops.collector.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.rest.DailyBar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.PostgresTestSupport;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.store.TickTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL 에서 daily_bar upsert 와 일봉 작업의 멱등성을 본다. Docker 가 없으면 건너뛴다.
 * 일봉 값은 모두 지어낸 합성값이다.
 */
@Testcontainers(disabledWithoutDocker = true)
class DailyBarRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private static final Instant T1 = LocalDate.of(2026, 11, 16).atTime(LocalTime.of(16, 0)).atZone(ZoneId.of("Asia/Seoul")).toInstant();

    private JdbcTemplate jdbc;
    private DailyBarRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.migrated(PG);
        jdbc.update("DELETE FROM daily_bar");
        repo = new DailyBarRepository(jdbc, new TickTradeRepository(jdbc)::instrumentId);
    }

    private static DailyBar bar(String date, String close, String mod) {
        return new DailyBar(LocalDate.parse(date), new BigDecimal("400.00"), new BigDecimal("401.00"),
                new BigDecimal("399.00"), new BigDecimal(close), 1000, new BigDecimal("50000000"), mod);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM daily_bar", Integer.class);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT * FROM daily_bar ORDER BY instrument_id, trade_date, market, source");
    }

    @Test
    void storesTheKisValuesAsGiven() {
        repo.upsert("A01612", "F", TickTrade.Source.KIS, List.of(bar("2026-11-13", "400.50", "N")), T1);

        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM daily_bar");
        assertEquals(LocalDate.of(2026, 11, 13), ((java.sql.Date) r.get("trade_date")).toLocalDate());
        assertEquals("F", r.get("market"));
        assertEquals("KIS", r.get("source"));
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) r.get("open_price")));
        assertEquals(0, new BigDecimal("400.50").compareTo((BigDecimal) r.get("close_price")));
        assertEquals(1000L, r.get("acc_volume"));
        assertEquals("N", r.get("mod_yn"));
    }

    @Test
    void theSameKeyIsOverwrittenAndFetchedAtMoves() {
        repo.upsert("A01612", "F", TickTrade.Source.KIS, List.of(bar("2026-11-13", "400.50", "N")), T1);
        Instant later = T1.plus(Duration.ofDays(1));
        repo.upsert("A01612", "F", TickTrade.Source.KIS, List.of(bar("2026-11-13", "400.75", "Y")), later);

        assertEquals(1, rows());
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM daily_bar");
        assertEquals(0, new BigDecimal("400.75").compareTo((BigDecimal) r.get("close_price")));
        assertEquals("Y", r.get("mod_yn"));
        assertEquals(later, ((java.sql.Timestamp) r.get("fetched_at")).toInstant());
    }

    @Test
    void marketsAndSourcesDoNotOverwriteEachOther() {
        List<DailyBar> one = List.of(bar("2026-11-13", "400.50", "N"));
        repo.upsert("A01612", "F", TickTrade.Source.KIS, one, T1);
        repo.upsert("A01612", "CM", TickTrade.Source.KIS, one, T1);
        repo.upsert("A01612", "F", TickTrade.Source.KIS_PAPER, one, T1);
        repo.upsert("A01703", "F", TickTrade.Source.KIS, one, T1);

        assertEquals(4, rows());
    }

    @Test
    void aBlankModYnIsStoredAsNull() {
        repo.upsert("A01612", "F", TickTrade.Source.KIS, List.of(bar("2026-11-13", "400.50", " ")), T1);
        assertNull(jdbc.queryForObject("SELECT mod_yn FROM daily_bar", String.class));
    }

    @Test
    void theDatabaseRejectsAnUnknownMarket() {
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.upsert("A01612", "O", TickTrade.Source.KIS, List.of(bar("2026-11-13", "400.50", "N")), T1));
    }

    /** docs/kis-daily-bar.md 의 sql 블록이 실제 스키마에서 실행되는지. 문서가 스키마와 어긋나면 여기서 잡힌다. */
    @Test
    void theQueriesInTheDocRunAgainstTheSchema() throws Exception {
        repo.upsert("A01612", "F", TickTrade.Source.SIMULATOR, List.of(bar("2026-11-13", "400.50", "N")), T1);
        String doc = java.nio.file.Files.readString(java.nio.file.Path.of("..", "docs", "kis-daily-bar.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("```sql\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(doc);
        int queries = 0;
        while (m.find()) {
            for (String sql : m.group(1).split(";")) {
                if (!sql.replaceAll("(?m)^\\s*--.*$", "").isBlank()) {
                    jdbc.queryForList(sql);
                    queries++;
                }
            }
        }
        assertEquals(4, queries);
    }

    @Test
    void runningTheJobTwiceOnTheSameDayLeavesTheSameRows() {
        DailyBarSource source = (market, symbol, from, to) -> List.of(bar("2026-11-13", "400.50", "N"), bar("2026-11-16", "401.25", "N"));
        var job = new DailyCloseJob(source, repo, TestCalendars.fixed(), FuturesContracts::empty, TickTrade.Source.KIS,
                Clock.fixed(T1, ZoneOffset.UTC), new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5,
                List.of("F", "CM")));

        job.runOnce();
        List<Map<String, Object>> first = snapshot();
        job.runOnce();

        assertEquals(4, rows(), "2 bars x 2 markets");
        assertEquals(first, snapshot(), "same clock, same KIS values: identical rows");
    }

    @Test
    void aSecondRunLaterTheSameDayChangesOnlyFetchedAt() {
        DailyBarSource source = (market, symbol, from, to) -> List.of(bar("2026-11-16", "401.25", "N"));
        var props = new DailyCloseProperties(true, LocalTime.of(16, 0), Duration.ofMinutes(15), 5, List.of("F"));
        new DailyCloseJob(source, repo, TestCalendars.fixed(), FuturesContracts::empty, TickTrade.Source.KIS,
                Clock.fixed(T1, ZoneOffset.UTC), props).runOnce();
        Map<String, Object> first = jdbc.queryForMap("SELECT * FROM daily_bar");

        new DailyCloseJob(source, repo, TestCalendars.fixed(), FuturesContracts::empty, TickTrade.Source.KIS,
                Clock.fixed(T1.plus(Duration.ofHours(3)), ZoneOffset.UTC), props).runOnce();
        Map<String, Object> second = jdbc.queryForMap("SELECT * FROM daily_bar");

        assertEquals(1, rows());
        Map<String, Object> a = new java.util.HashMap<>(first);
        Map<String, Object> b = new java.util.HashMap<>(second);
        a.remove("fetched_at");
        b.remove("fetched_at");
        assertEquals(a, b);
        assertEquals(Duration.ofHours(3),
                Duration.between(((java.sql.Timestamp) first.get("fetched_at")).toInstant(),
                        ((java.sql.Timestamp) second.get("fetched_at")).toInstant()));
    }
}
