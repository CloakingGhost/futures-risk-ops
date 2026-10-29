package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.instrument.InstrumentStore;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.ws.RealtimeClient;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 지표 9종이 Prometheus 출력에 나오고, 값이 기존 카운터(로그 ingest stats)와 같은지 시뮬레이터로 본다. DB 는 쓰지 않는다. */
class CollectorMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** docs/metrics.md 에 고정한 Prometheus 이름. */
    static final List<String> NAMES = List.of(
            "collector_messages_received_total",
            "collector_trade_to_receive_seconds",
            "collector_receive_to_commit_seconds",
            "collector_ws_state",
            "collector_ws_reconnects_total",
            "collector_missing_volume_total",
            "collector_market_session_open",
            "collector_token_expiry_timestamp_seconds",
            "collector_last_trade_received_timestamp_seconds");

    private final List<AutoCloseable> open = new ArrayList<>();
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @AfterEach
    void cleanup() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
        registry.close();
    }

    private static Instant kst(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(KST).toInstant();
    }

    /** 그 한국 시각부터 실제 속도로 흐르는 시계. 시뮬레이터와 수집기가 같이 쓴다(체결 시각과 수신 시각을 맞추려고). */
    private static Clock runningFrom(String dateTime) {
        Clock real = Clock.system(KST);
        return Clock.offset(real, Duration.between(real.instant(), kst(dateTime)));
    }

    private SimulatorServer simulator(Clock clock, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--price.seed=9", "--rate.ticks-per-second=50",
                "--pingpong.interval-seconds=0"));
        args.addAll(List.of(extra));
        SimulatorServer s = new SimulatorServer(SimulatorConfig.load(args.toArray(String[]::new), Map.of()), clock);
        s.start();
        open.add(s);
        return s;
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

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).functionCounter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    @Test
    void scrapeHasAllNineNamesAndTheValuesFollowTheExistingCounters() throws Exception {
        Clock clock = runningFrom("2026-11-02T10:00:00"); // 월요일 주간장
        SimulatorServer s = simulator(clock);
        AtomicLong quotes = new AtomicLong();
        IngestService svc = new IngestService(IngestServiceTest.autoProps(s.port(), true), TestCalendars.fixed(),
                List::size, b -> {
                    quotes.addAndGet(b.size());
                    return b.size();
                }, InstrumentStore.none(), null, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        new CollectorMetrics(svc).bindTo(registry);

        String idle = registry.scrape();
        for (String name : NAMES) {
            assertTrue(idle.contains(name), name + " is exported before anything happens");
        }
        assertTrue(idle.contains("collector_ws_state{state=\"IDLE\"} 1"), idle);
        assertEquals(0, counter(CollectorMetrics.MESSAGES_RECEIVED, "kind", "trade", "tr_id", "H0MFCNT0"));
        assertEquals(0, gauge(CollectorMetrics.LAST_TRADE_RECEIVED));

        svc.start();
        await(() -> svc.writer().writtenCount() >= 50 && quotes.get() > 0, "trades and quotes written");
        assertEquals(1, gauge(CollectorMetrics.WS_STATE, "state", "SUBSCRIBED"));
        assertEquals(0, gauge(CollectorMetrics.WS_STATE, "state", "IDLE"));
        assertEquals(1, gauge(CollectorMetrics.MARKET_SESSION_OPEN));
        assertEquals(0, gauge(CollectorMetrics.TOKEN_EXPIRY), "the simulator key never expires");

        // 시뮬레이터를 먼저 닫아 수신을 멈춘 뒤 수집기를 끄고, 기존 카운터와 비교한다
        s.close();
        await(() -> svc.client().state() != RealtimeClient.State.SUBSCRIBED, "connection lost");
        svc.stop();
        RealtimeClient c = svc.client();
        Timer tradeToReceive = registry.get(CollectorMetrics.TRADE_TO_RECEIVE).timer();
        Timer receiveToCommit = registry.get(CollectorMetrics.RECEIVE_TO_COMMIT).timer();
        await(() -> counter(CollectorMetrics.MESSAGES_RECEIVED, "kind", "trade", "tr_id", "H0IFCNT0")
                == c.tradeCount() && tradeToReceive.count() == c.tradeCount(), "trade counters settle");
        assertEquals(c.quoteCount(), counter(CollectorMetrics.MESSAGES_RECEIVED, "kind", "quote", "tr_id", "H0IFASP0"));
        assertEquals(0, counter(CollectorMetrics.MESSAGES_RECEIVED, "kind", "trade", "tr_id", "H0MFCNT0"),
                "no night trades in the day session");
        assertEquals(svc.writer().writtenCount(), receiveToCommit.count());
        assertTrue(tradeToReceive.mean(TimeUnit.SECONDS) < 2,
                "same clock on both sides, so only the whole-second rounding: " + tradeToReceive.mean(TimeUnit.SECONDS));
        assertTrue(receiveToCommit.mean(TimeUnit.SECONDS) < 2);
        assertEquals(c.recoveryCount(), counter(CollectorMetrics.WS_RECONNECTS));
        assertEquals(svc.gaps().reconnectMissingVolume(), counter(CollectorMetrics.MISSING_VOLUME, "kind", "reconnect"));
        assertEquals(svc.gaps().streamMissingVolume(), counter(CollectorMetrics.MISSING_VOLUME, "kind", "stream"));
        Instant last = svc.stalls().lastTradeAt();
        assertEquals(last.getEpochSecond() + last.getNano() / 1e9, gauge(CollectorMetrics.LAST_TRADE_RECEIVED), 1e-3);

        String after = registry.scrape();
        assertTrue(after.contains("collector_trade_to_receive_seconds_bucket"), "histogram buckets for p95");
        assertTrue(after.contains("collector_receive_to_commit_seconds_bucket"), "histogram buckets for p95");
        assertTrue(after.contains("collector_ws_state{state=\"IDLE\"} 1"), "stopped");
    }

    @Test
    void tradeToReceiveSeesTheDeliveryDelay() throws Exception {
        Clock clock = runningFrom("2026-11-02T10:00:00");
        SimulatorServer s = simulator(clock, "--delay.fixed-ms=1500");
        IngestService svc = new IngestService(IngestServiceTest.autoProps(s.port(), false), TestCalendars.fixed(),
                List::size, List::size, InstrumentStore.none(), null, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        new CollectorMetrics(svc).bindTo(registry);
        Timer tradeToReceive = registry.get(CollectorMetrics.TRADE_TO_RECEIVE).timer();
        svc.start();
        await(() -> tradeToReceive.count() >= 30, "delayed trades received");
        // 1.5초 지연 + bsop_hour 를 초로 자른 0~1초 → 1.5~2.5초
        double mean = tradeToReceive.mean(TimeUnit.SECONDS);
        assertTrue(mean >= 1.4 && mean <= 2.6, "mean " + mean);
    }

    @Test
    void marketSessionOpenFollowsTheCalendarEvenWhenTheGateIsOff() {
        IngestServiceTest.MutableClock clock = new IngestServiceTest.MutableClock(kst("2026-11-01T10:00:00")); // 일요일
        IngestService svc = new IngestService(IngestServiceTest.props(1, false, Duration.ofSeconds(5)),
                TestCalendars.fixed(), List::size, new OkHttpClient(), Map.of(), clock);
        open.add(svc::stop);
        new CollectorMetrics(svc).bindTo(registry);

        assertEquals(0, gauge(CollectorMetrics.MARKET_SESSION_OPEN), "Sunday");
        clock.set(kst("2026-11-02T08:45:00"));
        assertEquals(1, gauge(CollectorMetrics.MARKET_SESSION_OPEN), "day session opens");
        clock.set(kst("2026-11-02T16:00:00"));
        assertEquals(0, gauge(CollectorMetrics.MARKET_SESSION_OPEN), "between sessions");
        clock.set(kst("2026-11-02T18:30:00"));
        assertEquals(1, gauge(CollectorMetrics.MARKET_SESSION_OPEN), "night session");
        clock.set(kst("2026-12-25T10:00:00"));
        assertEquals(0, gauge(CollectorMetrics.MARKET_SESSION_OPEN), "holiday in the test calendar");
    }

    @Test
    void tokenExpiryIsTheIssueTimePlusTheAssumedTtlInKisMode() throws Exception {
        MockWebServer approval = new MockWebServer();
        approval.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-approval\"}"));
        approval.start();
        open.add(approval::shutdown);
        SimulatorServer s = simulator(Clock.system(KST));
        Instant issued = kst("2026-11-02T10:00:00");
        IngestServiceTest.MutableClock clock = new IngestServiceTest.MutableClock(issued);
        CollectorProperties base = IngestServiceTest.props(s.port(), false, Duration.ofSeconds(30));
        CollectorProperties p = new CollectorProperties(base.enabled(), base.source(), base.wsUrl(), base.symbols(),
                base.reconnect(), base.wsPingInterval(), base.session(), base.stallThreshold(), false,
                new CollectorProperties.Approval(CollectorProperties.Approval.Mode.KIS, approval.url("/").toString(),
                        "TEST_APP_KEY", "TEST_APP_SECRET", Duration.ofHours(24)),
                base.batch(), null, null);
        // 더미 값이다. 시뮬레이터는 접속키를 검사하지 않는다
        IngestService svc = new IngestService(p, TestCalendars.fixed(), List::size, new OkHttpClient(),
                Map.of("TEST_APP_KEY", "dummy-app-key", "TEST_APP_SECRET", "dummy-secret"), clock);
        open.add(svc::stop);
        new CollectorMetrics(svc).bindTo(registry);

        assertEquals(0, gauge(CollectorMetrics.TOKEN_EXPIRY), "not issued before connecting");
        svc.start();
        await(() -> svc.client().state() == RealtimeClient.State.SUBSCRIBED, "subscribed with the approval key");
        assertEquals(issued.plus(Duration.ofHours(24)).getEpochSecond(), gauge(CollectorMetrics.TOKEN_EXPIRY), 1e-3);
        assertEquals(1, approval.getRequestCount());
    }
}
