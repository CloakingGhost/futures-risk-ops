package com.futuresriskops.collector.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 체결을 큐에 모았다가 한 번에 DB 에 넣는다. 웹소켓 수신 스레드는 {@link #offer} 만 부르고 DB 를 기다리지 않는다.
 *
 * <p>배치는 {@code maxBatch} 건이 모이거나 {@code flushIntervalMs} 가 지나면 나간다.
 * 큐가 가득 차면 그 체결은 버리고 {@link #droppedCount()} 를 올린다(유실을 숨기지 않는다).
 * DB 쓰기가 실패하면 그 배치를 한 번 더 시도하고, 그래도 실패하면 버린 건수에 더한다.
 */
public final class TickBatchWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TickBatchWriter.class);

    private final BlockingQueue<TickTrade> queue;
    private final ToIntFunction<List<TickTrade>> sink;
    private final int maxBatch;
    private final long flushIntervalMs;
    private final Thread thread;
    private volatile boolean running = true;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** @param sink 배치를 넣고 실제로 들어간 건수를 돌려준다(보통 {@link TickTradeRepository#insertBatch}) */
    public TickBatchWriter(ToIntFunction<List<TickTrade>> sink, int queueCapacity, int maxBatch, long flushIntervalMs) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.sink = sink;
        this.maxBatch = maxBatch;
        this.flushIntervalMs = flushIntervalMs;
        // 데몬이 아닌 스레드다: 웹 서버가 없는 collector 프로세스를 살려 두고, 종료할 때 close() 가 남은 체결을 마저 쓴다
        this.thread = new Thread(this::loop, "tick-writer");
    }

    public void start() {
        thread.start();
    }

    public void offer(TickTrade t) {
        received.incrementAndGet();
        if (!queue.offer(t)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                LOG.warn("write queue full, dropped {} trades so far", n);
            }
        }
    }

    private void loop() {
        List<TickTrade> batch = new ArrayList<>(maxBatch);
        while (running || !queue.isEmpty()) {
            try {
                TickTrade first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                long deadline = System.nanoTime() + flushIntervalMs * 1_000_000L;
                while (batch.size() < maxBatch) {
                    long left = deadline - System.nanoTime();
                    TickTrade next = left > 0 ? queue.poll(left, TimeUnit.NANOSECONDS) : queue.poll();
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
            } catch (InterruptedException e) {
                running = false; // 남은 것은 아래에서 마저 쓴다
            }
            flush(batch);
        }
    }

    private void flush(List<TickTrade> batch) {
        if (batch.isEmpty()) {
            return;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                int inserted = sink.applyAsInt(batch);
                written.addAndGet(inserted);
                duplicates.addAndGet(batch.size() - inserted);
                batch.clear();
                return;
            } catch (RuntimeException e) {
                LOG.warn("batch write of {} trades failed (attempt {}): {}", batch.size(), attempt, e.toString());
            }
        }
        dropped.addAndGet(batch.size());
        batch.clear();
    }

    /** 큐에 남은 체결을 모두 쓴 뒤 멈춘다. */
    @Override
    public void close() {
        running = false;
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long receivedCount() {
        return received.get();
    }

    public long writtenCount() {
        return written.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long droppedCount() {
        return dropped.get();
    }
}
