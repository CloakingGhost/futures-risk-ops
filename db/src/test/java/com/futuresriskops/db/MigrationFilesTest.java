package com.futuresriskops.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 마이그레이션 파일의 구조만 확인한다. 실제 PostgreSQL 에서 적용되는지는
 * collector 의 TickTradeRepositoryTest 가 Testcontainers 로 확인한다(Docker 가 없으면 건너뜀).
 */
class MigrationFilesTest {

    private static String read(String name) throws IOException {
        try (InputStream in = MigrationFilesTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(in, name + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void v1CreatesTheThreeDayZeroTables() throws IOException {
        String sql = read("V1__init.sql").toLowerCase();
        for (String table : List.of("instrument", "futures_quote_snapshot", "tick_trade")) {
            assertTrue(sql.contains("create table " + table + " ("), "missing table " + table);
        }
    }

    @Test
    void tradeAndSnapshotTablesReferenceInstrumentAndMarkTheirSource() throws IOException {
        String sql = read("V1__init.sql").toLowerCase();
        assertEquals(2, count(sql, "references instrument (id)"));
        assertEquals(2, count(sql, "check (source in ('kis', 'simulator'))"));
    }

    @Test
    void v2AddsTradeDateSessionDedupKeyAndReplaySource() throws IOException {
        String sql = read("V2__tick_trade_trade_date_session.sql").toLowerCase();
        assertTrue(sql.contains("add column trade_date date"));
        assertTrue(sql.contains("add column session varchar(8)"));
        assertTrue(sql.contains("check (session in ('day', 'night'))"));
        assertTrue(sql.contains("check (source in ('kis', 'simulator', 'replay'))"));
        assertTrue(sql.contains("unique (instrument_id, trade_date, session, acc_volume)"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
