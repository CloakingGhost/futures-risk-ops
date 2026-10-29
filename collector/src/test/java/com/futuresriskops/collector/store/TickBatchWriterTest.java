package com.futuresriskops.collector.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TradingSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TickBatchWriterTest {

    private static TickTrade trade(long accVolume) {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        return new TickTrade("A01612", now, now, LocalDate.of(2026, 10, 27), TradingSession.DAY,
                new BigDecimal("400.00"), 1, accVolume, 50_000, TickTrade.Source.SIMULATOR);
    }

    @Test
    void groupsTradesIntoBatchesAndWritesEverythingOnClose() {
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        TickBatchWriter<TickTrade> w = new TickBatchWriter<>(b -> {
            batchSizes.add(b.size());
            return b.size();
        }, 10_000, 100, 50);
        w.start();
        for (int i = 1; i <= 1_000; i++) {
            w.offer(trade(i));
        }
        w.close();
        assertEquals(1_000, w.writtenCount());
        assertEquals(1_000, batchSizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(batchSizes.stream().allMatch(s -> s <= 100));
        assertTrue(batchSizes.size() < 100, "should write in batches, got " + batchSizes.size());
    }

    @Test
    void countsDuplicatesAndDropsInsteadOfHidingThem() {
        TickBatchWriter<TickTrade> w = new TickBatchWriter<>(b -> b.size() / 2, 5, 100, 50);
        for (int i = 1; i <= 8; i++) {
            w.offer(trade(i)); // 시작 전이라 큐 5칸만 찬다
        }
        assertEquals(3, w.droppedCount());
        w.start();
        w.close();
        assertEquals(8, w.receivedCount());
        assertEquals(2, w.writtenCount());
        assertEquals(3, w.duplicateCount());
    }

    @Test
    void retriesAFailedBatchOnce() {
        List<Integer> calls = new ArrayList<>();
        TickBatchWriter<TickTrade> w = new TickBatchWriter<>(b -> {
            calls.add(b.size());
            if (calls.size() == 1) {
                throw new IllegalStateException("db down");
            }
            return b.size();
        }, 100, 100, 50);
        w.offer(trade(1));
        w.start();
        w.close();
        assertEquals(1, w.writtenCount());
        assertEquals(0, w.droppedCount());
        assertEquals(2, calls.size());
    }
}
