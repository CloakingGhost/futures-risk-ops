package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.instrument.InstrumentStore;
import com.futuresriskops.collector.instrument.SubscriptionPlanner;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.ws.RealtimeClient;
import com.futuresriskops.collector.ws.Subscription;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                Duration.ofSeconds(20),
                new CollectorProperties.Session("unused-in-tests", gating, Duration.ofMinutes(1)), stallThreshold,
                false, new CollectorProperties.Approval(CollectorProperties.Approval.Mode.NONE, "https://unused",
                        "UNUSED_KEY_ENV", "UNUSED_SECRET_ENV"),
                new CollectorProperties.Batch(100_000, 500, 50), null, null);
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
        assertEquals("state=IDLE, front=-, subs=0, connects=0, disconnects=0, recoveries=0, last-recovery-ms=-1, max-recovery-ms=-1, "
                + "trades=0, written=0, duplicates=0, dropped=0, reconnect-gaps=0, reconnect-missing=0, "
                + "reconnect-missing-rate=0.0000, stream-gaps=0, stream-missing=0, repeats=0, stalls=0, out-of-session-connects=0, parse-errors=0, subscribe-errors=0, "
                + "quotes=0, quotes-written=0, quotes-dropped=0",
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
        await(() -> svc.gaps().streamGapCount() >= 1, "the trades dropped during the stall show up as a gap");
        assertTrue(svc.gaps().streamMissingVolume() > 0);
        assertEquals(0, svc.gaps().reconnectGapCount(), "no reconnect happened, so it is a stream gap");
        assertEquals(0, svc.client().disconnectCount(), "the connection itself never dropped");
    }

    static CollectorProperties autoProps(int port, boolean quotes) {
        CollectorProperties p = props(port, false, Duration.ofSeconds(30));
        return new CollectorProperties(p.enabled(), p.source(), p.wsUrl(), List.of(), p.reconnect(), p.wsPingInterval(),
                p.session(), p.stallThreshold(), false, p.approval(), p.batch(),
                new CollectorProperties.Instruments(SubscriptionPlanner.Mode.AUTO, quotes,
                        new CollectorProperties.Master(false, null, null)), null);
    }

    @Test
    void rollsOverFromTheDayBeforeExpiryAndSwitchesToNightTrades() throws Exception {
        SimulatorServer s = simulator();
        MutableClock clock = new MutableClock(kst("2026-12-08T10:00:00")); // 12월물 만기(12/10) 이틀 전
        AtomicLong quotes = new AtomicLong();
        IngestService svc = new IngestService(autoProps(s.port(), true), TestCalendars.fixed(), batch -> {
            sunk.addAndGet(batch.size());
            return batch.size();
        }, batch -> {
            quotes.addAndGet(batch.size());
            return batch.size();
        }, InstrumentStore.none(), null, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        svc.start();
        await(() -> svc.client().subscriptions().equals(List.of(new Subscription("H0IFCNT0", "A01612"),
                new Subscription("H0IFASP0", "A01612"))), "front month trades and quotes");
        await(() -> sunk.get() > 0 && quotes.get() > 0, "trades and quotes are written");
        assertEquals(Optional.of(YearMonth.of(2026, 12)), svc.frontMonth());

        clock.set(kst("2026-12-09T09:00:00")); // 만기 전날: 다음 결제월물도 같이
        svc.supervise();
        await(() -> svc.client().subscriptions().size() == 4, "both months during the overlap");

        clock.set(kst("2026-12-10T15:46:00")); // 만기일 주간장 끝 → 다음 세션은 야간장, 3월물만
        svc.supervise();
        await(() -> svc.client().subscriptions().equals(List.of(new Subscription("H0MFCNT0", "A01703"))),
                "only night trades of the March contract");
        assertEquals(Optional.of(YearMonth.of(2027, 3)), svc.frontMonth());
        long before = svc.client().tradeCount();
        await(() -> svc.client().tradeCount() > before + 5, "night trades of the March contract");
        assertEquals(1, svc.client().connectCount(), "subscription changes do not reconnect");
        assertEquals(0, svc.client().parseErrorCount());
        assertEquals(0, svc.client().subscribeErrorCount());
    }

    @Test
    void masterCodesReplaceRuleCodesOnceDownloaded() throws Exception {
        SimulatorServer s = simulator();
        MutableClock clock = new MutableClock(kst("2026-10-29T10:00:00"));
        // 합성 마스터: 12월물 코드를 규칙과 다르게 둬서 마스터 값이 쓰이는지 본다
        byte[] master = "1|A016ZZ|KR4A016ZZ|F 202612| |00000.00|1|2001|KOSPI200\n".getBytes(StandardCharsets.UTF_8);
        CollectorProperties base = autoProps(s.port(), false);
        CollectorProperties p = new CollectorProperties(base.enabled(), base.source(), base.wsUrl(), base.symbols(),
                base.reconnect(), base.wsPingInterval(), base.session(), base.stallThreshold(), false, base.approval(),
                base.batch(), new CollectorProperties.Instruments(SubscriptionPlanner.Mode.AUTO, false,
                        new CollectorProperties.Master(true, "https://unused", LocalTime.of(7, 30))), null);
        IngestService svc = new IngestService(p, TestCalendars.fixed(), batch -> batch.size(), batch -> batch.size(),
                InstrumentStore.none(), () -> master, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        svc.start();
        await(() -> svc.master().lastSuccess() != null, "master applied");
        await(() -> svc.client().subscriptions().equals(List.of(new Subscription("H0IFCNT0", "A016ZZ"))),
                "the master code is subscribed");
    }
}
