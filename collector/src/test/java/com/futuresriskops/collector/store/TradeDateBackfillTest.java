package com.futuresriskops.collector.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.collector.session.TestCalendars;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Day 1 임시 규칙으로 채운 거래일을 캘린더 규칙으로 다시 계산한다. 행은 손으로 만든 합성값. Docker 가 없으면 건너뛴다. */
@Testcontainers(disabledWithoutDocker = true)
class TradeDateBackfillTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private JdbcTemplate jdbc;
    private long instrument;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.migrated(PG);
        jdbc.update("TRUNCATE tick_trade");
        instrument = new TickTradeRepository(jdbc).instrumentId("A01612");
    }

    private void insert(String tradedAtKst, String tradeDate, String session, long acc) {
        jdbc.update("""
                INSERT INTO tick_trade (instrument_id, traded_at, received_at, trade_date, session, price, qty,
                                        acc_volume, open_interest, source)
                VALUES (?, ?, ?, ?, ?, 400.00, 1, ?, 0, 'SIMULATOR')
                """, instrument, ts(tradedAtKst), ts(tradedAtKst), Date.valueOf(tradeDate), session, acc);
    }

    private static Timestamp ts(String kst) {
        return Timestamp.from(ZonedDateTime.parse(kst + "+09:00[Asia/Seoul]").toInstant());
    }

    private LocalDate tradeDateOf(long acc) {
        return jdbc.queryForObject("SELECT trade_date FROM tick_trade WHERE acc_volume = ?", Date.class, acc)
                .toLocalDate();
    }

    @Test
    void movesNightTradesToTheNextTradingDayAndLeavesDayTradesAlone() {
        insert("2026-10-30T10:00:00", "2026-10-30", "DAY", 1);   // 금 주간: 그대로
        insert("2026-10-30T20:00:00", "2026-10-30", "NIGHT", 2); // 금 야간(자정 전): → 월
        insert("2026-10-31T02:00:00", "2026-10-31", "NIGHT", 3); // 금 야간(토 새벽): → 월

        TradeDateBackfill.Result r = new TradeDateBackfill(jdbc).run(TestCalendars.fixed());

        assertEquals(new TradeDateBackfill.Result(3, 2, 0), r);
        assertEquals(LocalDate.of(2026, 10, 30), tradeDateOf(1));
        assertEquals(LocalDate.of(2026, 11, 2), tradeDateOf(2));
        assertEquals(LocalDate.of(2026, 11, 2), tradeDateOf(3));
        // 다시 돌려도 바뀌는 것이 없다
        assertEquals(new TradeDateBackfill.Result(3, 0, 0), new TradeDateBackfill(jdbc).run(TestCalendars.fixed()));
    }

    @Test
    void aRowThatWouldCollideIsLeftAsItIsAndCounted() {
        // 같은 누적거래량이 이미 월요일 야간 행으로 있으면(같은 체결로 본다) 옮기지 않는다
        insert("2026-11-01T23:00:00", "2026-11-02", "NIGHT", 7); // 일요일 밤 시뮬레이터 체결, 이미 월요일로 적힘
        insert("2026-10-30T20:00:00", "2026-10-30", "NIGHT", 7); // 금 야간, 옮기면 위와 겹친다

        TradeDateBackfill.Result r = new TradeDateBackfill(jdbc).run(TestCalendars.fixed());

        assertEquals(1, r.conflicts());
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM tick_trade", Long.class));
    }
}
