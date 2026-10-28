package com.futuresriskops.collector;

import com.futuresriskops.collector.monitor.GapDetector;
import com.futuresriskops.collector.monitor.SessionGate;
import com.futuresriskops.collector.monitor.StallWatch;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.Backoff;
import com.futuresriskops.collector.ws.KisApprovalKeyClient;
import com.futuresriskops.collector.ws.RealtimeClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 웹소켓 수신 → 파싱 → 거래일·세션 계산 → 누락 감지 → 배치 적재를 묶어 스프링 시작·종료에 맞춰 켜고 끈다.
 * 1초마다 세션 게이트(장 시간에만 접속)와 수신 공백을 점검하고, 10초마다 통계를 로그로 남긴다.
 * 끌 때는 큐에 남은 체결을 다 쓴다.
 */
public class IngestService implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);
    private static final long STATS_INTERVAL_SECONDS = 10;

    private final TickBatchWriter writer;
    private final RealtimeClient client;
    private final SessionGate gate;
    private final GapDetector gaps = new GapDetector();
    private final StallWatch stalls;
    private final Clock clock;
    private final AtomicLong outOfSessionConnects = new AtomicLong();
    private final ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ingest-supervisor");
        t.setDaemon(true);
        return t;
    });
    private Boolean gateOpen; // supervisor 스레드에서만
    private volatile boolean running;

    public IngestService(CollectorProperties p, SessionCalendar calendar, ToIntFunction<List<TickTrade>> sink,
                         OkHttpClient http, Map<String, String> env, Clock clock) {
        ApprovalKeyProvider keys = p.approval().mode() == CollectorProperties.Approval.Mode.KIS
                ? new KisApprovalKeyClient(http, p.approval().restBaseUrl(), env, p.approval().appKeyEnv(),
                        p.approval().appSecretEnv())
                : ApprovalKeyProvider.simulator();
        TickTrade.Source source = p.source();
        this.clock = clock;
        this.gate = new SessionGate(calendar, p.session().gating(), p.session().connectLead());
        this.stalls = new StallWatch(p.stallThreshold());
        this.writer = new TickBatchWriter(sink, p.batch().queueCapacity(), p.batch().maxSize(),
                p.batch().flushIntervalMs());
        Backoff backoff = new Backoff(p.reconnect().initialBackoff(), p.reconnect().maxBackoff(),
                p.reconnect().maxJitter(), new Random());
        this.client = new RealtimeClient(http, p.wsUrl(), p.symbols(), keys, (trade, receivedAt) -> {
            TickTrade t = TickTrade.of(trade, receivedAt, source, calendar);
            gaps.observe(t.symbol(), t.tradeDate(), t.session(), t.qty(), t.accVolume());
            stalls.onTrade(receivedAt);
            writer.offer(t);
        }, backoff, clock);
        client.setStateListener(this::onStateChange);
    }

    private void onStateChange(RealtimeClient.State from, RealtimeClient.State to, Instant at) {
        if (to == RealtimeClient.State.SUBSCRIBED) {
            stalls.onSubscribed(at);
        }
        if (to == RealtimeClient.State.CONNECTING) {
            // 새 연결을 열기 전에 표시한다. 그 연결의 첫 체결보다 반드시 먼저다(SUBSCRIBED 는 첫 체결보다 늦게 처리될 수 있다)
            gaps.onNewConnection();
        }
        if (to == RealtimeClient.State.CONNECTING && !gate.wantConnected(at)) {
            long n = outOfSessionConnects.incrementAndGet();
            LOG.warn("connecting outside the trading session (#{})", n);
        }
    }

    @Override
    public void start() {
        writer.start();
        supervise();
        supervisor.scheduleAtFixedRate(this::superviseSafely, 1, 1, TimeUnit.SECONDS);
        // Windows 에서 Ctrl+C 로 gradle bootRun 을 끄면 종료 훅이 돌지 않아 마지막 요약이 안 남는다. 그래서 주기적으로도 남긴다.
        supervisor.scheduleAtFixedRate(() -> LOG.info("ingest stats: {}", summary()), STATS_INTERVAL_SECONDS,
                STATS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        running = true;
    }

    private void superviseSafely() {
        try {
            supervise();
        } catch (RuntimeException e) {
            LOG.warn("supervisor check failed: {}", e.toString());
        }
    }

    /** 세션 게이트와 수신 공백 점검 한 번. 테스트는 시계를 옮긴 뒤 직접 부른다. */
    void supervise() {
        Instant now = clock.instant();
        boolean want = gate.wantConnected(now);
        if (gateOpen == null || gateOpen != want) {
            if (gate.enabled()) {
                LOG.info(want ? "session gate open: connecting" : "session gate closed: staying disconnected until the next session {}",
                        gate.nextOpenText(now));
            }
            gateOpen = want;
            client.setActive(want);
        }
        stalls.check(now, client.state() == RealtimeClient.State.SUBSCRIBED && gate.inSession(now));
    }

    /**
     * 로그에 남기는 한 줄 요약. trades 는 파싱한 체결, duplicates 는 이미 있어서 DB 가 버린 체결이다.
     * reconnect-* 는 끊긴 동안 놓친 체결(확실한 누락), stream-* 는 연결 중 누적거래량 빈틈(묶음 전송일 수 있다).
     */
    String summary() {
        return "state=" + client.state() + ", connects=" + client.connectCount() + ", disconnects="
                + client.disconnectCount() + ", recoveries=" + client.recoveryCount() + ", last-recovery-ms="
                + client.lastRecoveryMillis() + ", max-recovery-ms=" + client.maxRecoveryMillis() + ", trades="
                + client.tradeCount() + ", written=" + writer.writtenCount() + ", duplicates="
                + writer.duplicateCount() + ", dropped=" + writer.droppedCount() + ", reconnect-gaps="
                + gaps.reconnectGapCount() + ", reconnect-missing=" + gaps.reconnectMissingVolume()
                + ", reconnect-missing-rate=" + String.format(Locale.ROOT, "%.4f", gaps.reconnectMissingRate())
                + ", stream-gaps=" + gaps.streamGapCount() + ", stream-missing=" + gaps.streamMissingVolume()
                + ", repeats=" + gaps.repeatCount()
                + ", stalls=" + stalls.stallCount() + ", out-of-session-connects=" + outOfSessionConnects.get()
                + ", parse-errors=" + client.parseErrorCount() + ", subscribe-errors=" + client.subscribeErrorCount();
    }

    @Override
    public void stop() {
        supervisor.shutdownNow();
        client.close();
        writer.close();
        running = false;
        LOG.info("ingest stopped: {}", summary());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public RealtimeClient client() {
        return client;
    }

    public TickBatchWriter writer() {
        return writer;
    }

    public GapDetector gaps() {
        return gaps;
    }

    public StallWatch stalls() {
        return stalls;
    }

    public long outOfSessionConnectCount() {
        return outOfSessionConnects.get();
    }
}
