package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 마이그레이션 파일의 구조만 확인한다. DB 에서 실제로 실행되는지는
 * docker compose 로 PostgreSQL 을 띄우고 collector 를 시작해 확인한다(docs/dev-setup.md).
 */
class MigrationFilesTest {

    private static String readV1() throws IOException {
        try (InputStream in = MigrationFilesTest.class.getResourceAsStream("/db/migration/V1__init.sql")) {
            assertNotNull(in, "V1__init.sql must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void v1CreatesTheThreeDayZeroTables() throws IOException {
        String sql = readV1().toLowerCase();
        for (String table : List.of("instrument", "futures_quote_snapshot", "tick_trade")) {
            assertTrue(sql.contains("create table " + table + " ("), "missing table " + table);
        }
    }

    @Test
    void tradeAndSnapshotTablesReferenceInstrumentAndMarkTheirSource() throws IOException {
        String sql = readV1().toLowerCase();
        assertEquals(2, count(sql, "references instrument (id)"));
        assertEquals(2, count(sql, "check (source in ('kis', 'simulator'))"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
