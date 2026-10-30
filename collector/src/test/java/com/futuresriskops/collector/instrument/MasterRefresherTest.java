package com.futuresriskops.collector.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TestCalendars;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MasterRefresherTest {

    /** 메모리 저장소. */
    static final class MemoryStore implements InstrumentStore {
        final List<FuturesContract> rows = new ArrayList<>();
        int upserts;

        @Override
        public int upsertFutures(List<FuturesContract> contracts, Instant at) {
            upserts++;
            rows.clear();
            rows.addAll(contracts);
            return contracts.size();
        }

        @Override
        public List<FuturesContract> loadFutures() {
            return List.copyOf(rows);
        }
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T22:30:00Z"), ZoneOffset.UTC);

    @Test
    void aGoodMasterIsUpsertedAndReplacesTheList() {
        MemoryStore store = new MemoryStore();
        MasterRefresher r = new MasterRefresher(() -> KisMasterParserTest.cp949(KisMasterParserTest.SYNTHETIC), store,
                TestCalendars.fixed(), CLOCK);
        assertTrue(r.refresh());
        assertEquals(1, store.upserts);
        assertEquals(2, store.rows.size());
        assertEquals("master", r.current().origin());
        assertTrue(r.current().known(YearMonth.of(2027, 3)));
        assertNotNull(r.lastSuccess());
    }

    @Test
    void aFailedDownloadKeepsWhatWasLoadedFromTheDatabase() {
        MemoryStore store = new MemoryStore();
        store.rows.add(new FuturesContract("A01612", "KR4A016C0004", "F 202612", YearMonth.of(2026, 12), 1));
        MasterRefresher r = new MasterRefresher(() -> {
            throw new IOException("network down");
        }, store, TestCalendars.fixed(), CLOCK);
        r.loadFromStore();
        assertFalse(r.refresh());
        assertEquals("db", r.current().origin());
        assertEquals(1, r.current().size());
        assertEquals(0, store.upserts);
        assertNull(r.lastSuccess());
    }

    @Test
    void aMasterWithoutKospi200FuturesIsIgnored() {
        MemoryStore store = new MemoryStore();
        MasterRefresher r = new MasterRefresher(() -> KisMasterParserTest.cp949("B|A05611|KR4A056B0006|미니F 202611| |00000.00|1|2001|KOSPI200\n"),
                store, TestCalendars.fixed(), CLOCK);
        assertFalse(r.refresh());
        assertEquals(0, store.upserts);
        assertEquals("none", r.current().origin());
    }

    @Test
    void aDatabaseFailureStillUpdatesTheCodesInMemory() {
        InstrumentStore broken = new InstrumentStore() {
            @Override
            public int upsertFutures(List<FuturesContract> contracts, Instant at) {
                throw new IllegalStateException("db down");
            }

            @Override
            public List<FuturesContract> loadFutures() {
                throw new IllegalStateException("db down");
            }
        };
        MasterRefresher r = new MasterRefresher(() -> KisMasterParserTest.cp949(KisMasterParserTest.SYNTHETIC), broken,
                TestCalendars.fixed(), CLOCK);
        r.loadFromStore();
        assertTrue(r.refresh());
        assertEquals(2, r.current().size());
    }
}
