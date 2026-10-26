package com.futuresriskops.simulator;

import com.futuresriskops.simulator.config.SimulatorConfig;
import com.futuresriskops.simulator.kis.KisFrames;
import com.futuresriskops.simulator.ws.WebSocketConnection;

import java.time.Clock;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 연결 하나의 상태와 장애 주입. 시세 자체는 종목별 피드가 만들고,
 * 지연·끊김·조용한 중단은 연결마다 따로(연결 시각 기준) 적용한다.
 * 그래서 재접속하면 타이머가 처음부터 다시 시작한다.
 */
final class ClientSession {

    private static final System.Logger LOG = System.getLogger(ClientSession.class.getName());

    private final WebSocketConnection conn;
    private final SimulatorConfig cfg;
    private final Clock clock;
    private final Random rng;
    private final long startNanos = System.nanoTime();
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    /** PINGPONG·시간 기반 끊김처럼 시각에 맞춰 한 번씩 실행하는 일. */
    private final ScheduledExecutorService timer;
    /** 체결 전송 대기열: 전달 시각 순서(같으면 도착 순서)로만 나간다. */
    private final DelayQueue<Delivery> queue = new DelayQueue<>();
    private final Thread senderThread;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong droppedByStall = new AtomicLong();
    private final AtomicLong pongs = new AtomicLong();

    private long lastDeliverNanos; // lock 으로 보호
    private long deliverySeq;      // lock 으로 보호

    /** 전달 시각이 되면 꺼낼 수 있는 체결 프레임. */
    private static final class Delivery implements Delayed {
        final long deliverAtNanos;
        final long seq;
        final String frame;

        Delivery(long deliverAtNanos, long seq, String frame) {
            this.deliverAtNanos = deliverAtNanos;
            this.seq = seq;
            this.frame = frame;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deliverAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            Delivery o = (Delivery) other;
            int c = Long.compare(deliverAtNanos, o.deliverAtNanos);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    ClientSession(WebSocketConnection conn, SimulatorConfig cfg, Clock clock, long rngSeed) {
        this.conn = conn;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = new Random(rngSeed);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-timer");
            t.setDaemon(true);
            return t;
        });
        this.senderThread = new Thread(this::sendLoop, "ws-sender");
        this.senderThread.setDaemon(true);
        this.lastDeliverNanos = startNanos;
    }

    WebSocketConnection connection() {
        return conn;
    }

    Set<String> subscriptions() {
        return subscriptions;
    }

    long sentCount() {
        return sent.get();
    }

    long stalledDropCount() {
        return droppedByStall.get();
    }

    long pongCount() {
        return pongs.get();
    }

    void onPong() {
        pongs.incrementAndGet();
    }

    /** 연결 직후 한 번 부른다: PINGPONG 주기와 시간 기반 끊김을 예약한다. */
    void start() {
        senderThread.start();
        double ping = cfg.pingpong().intervalSeconds();
        if (ping > 0) {
            long period = (long) (ping * 1_000_000_000L);
            schedule(() -> conn.sendText(KisFrames.pingPong(clock)), period, period);
        }
        double after = cfg.disconnect().afterSeconds();
        if (after > 0) {
            timer.schedule(() -> dropConnection("disconnect.after-seconds"), (long) (after * 1_000_000_000L),
                    TimeUnit.NANOSECONDS);
        }
    }

    /** 피드가 체결 프레임을 넘겨준다. 호출한 스레드를 막지 않는다. */
    void deliver(String frame) {
        if (!conn.isOpen()) {
            return;
        }
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
        if (cfg.stall().isStalledAt(elapsedSeconds)) {
            droppedByStall.incrementAndGet();
            return;
        }
        double p = cfg.disconnect().probabilityPerTick();
        if (p > 0 && rng.nextDouble() < p) {
            runOnTimer(() -> dropConnection("disconnect.probability-per-tick"));
            return;
        }
        long delayNanos = (long) (nextDelayMillis() * 1_000_000L);
        long now = System.nanoTime();
        synchronized (this) {
            long deliverAt = Math.max(lastDeliverNanos, now + delayNanos); // 순서 유지: 이전 체결보다 앞서지 않는다
            lastDeliverNanos = deliverAt;
            queue.add(new Delivery(deliverAt, deliverySeq++, frame));
        }
    }

    private void sendLoop() {
        try {
            while (true) {
                Delivery d = queue.take();
                if (conn.sendText(d.frame)) {
                    sent.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double nextDelayMillis() {
        SimulatorConfig.Delay d = cfg.delay();
        double ms = d.fixedMs();
        if (d.jitterMs() > 0) {
            ms += rng.nextDouble() * d.jitterMs();
        }
        if (d.spikeProbability() > 0 && rng.nextDouble() < d.spikeProbability()) {
            ms += d.spikeMs();
        }
        return ms;
    }

    private void dropConnection(String reason) {
        LOG.log(System.Logger.Level.INFO, "dropping connection {0} ({1}, abrupt={2})",
                conn.remote(), reason, cfg.disconnect().abrupt());
        if (cfg.disconnect().abrupt()) {
            conn.abort();
        } else {
            conn.close(1001, "simulator: forced disconnect");
        }
    }

    private void schedule(Runnable r, long initialDelayNanos, long periodNanos) {
        timer.scheduleAtFixedRate(r, initialDelayNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    private void runOnTimer(Runnable r) {
        try {
            timer.execute(r);
        } catch (RejectedExecutionException ignored) {
            // 세션이 이미 닫힘
        }
    }

    void shutdown() {
        timer.shutdownNow();
        senderThread.interrupt();
        queue.clear();
    }
}
