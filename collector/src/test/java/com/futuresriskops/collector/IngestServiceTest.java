package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.ws.RealtimeClient;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** DB 없이(적재는 건수만 센다) 시뮬레이터에 붙여 세션 게이트·수신 공백·누락 감지를 본다. */
class IngestServiceTest {

    private final List<AutoCloseable> open = new ArrayList<>();
    private final AtomicLong sunk = new AtomicLong();

    @AfterEach
    void cleanup() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    /** 테스트에서 옮길 수 있는 시계. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static CollectorProperties props(int port, boolean gating, Duration stallThreshold) {
        return new CollectorProperties(true, TickTrade.Source.SIMULATOR, "ws://127.0.0.1:" + port + "/tryitout",
                List.of("A01612"),
                new CollectorProperties.Reconnect(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ZERO),
                new CollectorProperties.Session("unused-in-tests", gating, Duration.ofMinutes(1)), stallThreshold,
                false, new CollectorProperties.Approval(CollectorProperties.Approval.Mode.NONE, "https://unused",
                        "UNUSED_KEY_ENV", "UNUSED_SECRET_ENV"),
                new CollectorProperties.Batch(100_000, 500, 50));
    }

    private SimulatorServer simulator(String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--price.seed=5",
                "--rate.ticks-per-second=50", "--pingpong.interval-seconds=0"));
        args.addAll(List.of(extra));
        SimulatorServer s = new SimulatorServer(SimulatorConfig.load(args.toArray(String[]::new), Map.of()));
        s.start();
        open.add(s);
        return s;
    }

    private IngestService service(CollectorProperties p, Clock clock) {
        IngestService svc = new IngestService(p, TestCalendars.fixed(), batch -> {
            sunk.addAndGet(batch.size());
            return batch.size();
        }, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        return svc;
    }

    private static Instant kst(String dateTime) {
        return ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]").toInstant();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void summaryShowsEveryCounterEvenBeforeAnythingHappened() {
        IngestService svc = service(props(1, false, Duration.ofSeconds(5)), Clock.systemUTC());
        assertEquals("state=IDLE, connects=0, disconnects=0, recoveries=0, last-recovery-ms=-1, max-recovery-ms=-1, "
                + "trades=0, written=0, duplicates=0, dropped=0, gaps=0, missing-volume=0, missing-rate=0.0000, "
                + "repeats=0, stalls=0, out-of-session-connects=0, parse-errors=0, subscribe-errors=0",
                svc.summary());
    }

    @Test
    void connectsOnlyFromOneMinuteBeforeTheSessionUntilItCloses() throws Exception {
        SimulatorServer s = simulator();
        MutableClock clock = new MutableClock(kst("2026-11-01T10:00:00")); // 일요일
        IngestService svc = service(props(s.port(), true, Duration.ofSeconds(30)), clock);
        svc.start();
        Thread.sleep(300);
        assertEquals(RealtimeClient.State.IDLE, svc.client().state());
        assertEquals(0, svc.client().connectCount(), "no connection on Sunday");

        clock.set(kst("2026-11-02T08:43:30")); // 월요일, 1분 30초 전
        svc.supervise();
        Thread.sleep(300);
        assertEquals(0, svc.client().connectCount(), "too early");

        clock.set(kst("2026-11-02T08:44:00")); // 1분 전
        svc.supervise();
        await(() -> svc.client().state() == RealtimeClient.State.SUBSCRIBED, "subscribed before the open");
        await(() -> sunk.get() > 0, "trades flow and are written");

        clock.set(kst("2026-11-02T15:45:00")); // 주간장 끝
        svc.supervise();
        await(() -> svc.client().state() == RealtimeClient.State.IDLE, "disconnected at the close");
        await(() -> s.sessionCount() == 0, "server sees no connection");
        assertEquals(1, svc.client().connectCount());
        assertEquals(0, svc.outOfSessionConnectCount());
    }

    @Test
    void silentStallIsDetectedAndTheMissedVolumeIsCounted() throws Exception {
        // 연결 0.5초 뒤 2초 동안 체결을 보내지 않는다(그동안 만든 체결은 버린다)
        SimulatorServer s = simulator("--stall.after-seconds=0.5", "--stall.duration-seconds=2");
        IngestService svc = service(props(s.port(), false, Duration.ofMillis(700)), Clock.systemUTC());
        svc.start();
        await(() -> svc.stalls().stallCount() == 1, "one stall");
        await(() -> !svc.stalls().stalled(), "trades resumed");
        await(() -> svc.gaps().gapCount() >= 1, "the trades dropped during the stall show up as a gap");
        assertTrue(svc.gaps().missingVolume() > 0);
        assertEquals(0, svc.client().disconnectCount(), "the connection itself never dropped");
    }
}
