package com.futuresriskops.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void v3AddsSourceToTheDedupKey() throws IOException {
        String sql = read("V3__tick_trade_dedup_by_source.sql").toLowerCase();
        assertTrue(sql.contains("drop constraint uq_tick_trade_dedup"));
        assertTrue(sql.contains("unique (instrument_id, trade_date, session, source, acc_volume)"));
    }

    @Test
    void v4AllowsKisPaperAsASource() throws IOException {
        String sql = read("V4__tick_trade_source_kis_paper.sql").toLowerCase();
        assertTrue(sql.contains("check (source in ('kis', 'kis_paper', 'simulator', 'replay'))"));
    }

    @Test
    void v5AddsMasterColumnsAndTheQuoteTable() throws IOException {
        String sql = read("V5__instrument_master_and_tick_quote.sql").toLowerCase();
        for (String col : List.of("standard_code", "contract_month", "month_class", "master_updated_at")) {
            assertTrue(sql.contains("alter table instrument add column " + col), "missing column " + col);
        }
        assertTrue(sql.contains("create table tick_quote ("));
        assertTrue(sql.contains("references instrument (id)"));
        assertTrue(sql.contains("check (source in ('kis', 'kis_paper', 'simulator', 'replay'))"));
    }

    @Test
    void v6AddsNullableBasisColumnsToTickTrade() throws IOException {
        String sql = read("V6__tick_trade_basis.sql").toLowerCase();
        for (String col : List.of("market_basis", "theoretical_price", "disparity_rate")) {
            assertTrue(sql.contains("alter table tick_trade add column " + col + " numeric"), "missing column " + col);
        }
        assertFalse(sql.contains("not null"), "basis columns must stay nullable for rows before V6");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
