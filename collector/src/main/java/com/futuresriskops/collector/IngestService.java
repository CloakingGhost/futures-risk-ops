package com.futuresriskops.collector;

import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.instrument.InstrumentStore;
import com.futuresriskops.collector.instrument.MasterDownloader;
import com.futuresriskops.collector.instrument.MasterRefresher;
import com.futuresriskops.collector.instrument.SubscriptionPlanner;
import com.futuresriskops.collector.monitor.GapDetector;
import com.futuresriskops.collector.monitor.SessionGate;
import com.futuresriskops.collector.monitor.StallWatch;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.store.TickQuote;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.Backoff;
import com.futuresriskops.collector.ws.KisApprovalKeyClient;
import com.futuresriskops.collector.ws.RealtimeClient;
import com.futuresriskops.collector.ws.Subscription;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
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
 * 1초마다 구독 목록(근월물·주간/야간 TR), 세션 게이트(장 시간에만 접속), 수신 공백, 마스터 갱신 시각을 점검하고,
 * 10초마다 통계를 로그로 남긴다. 끌 때는 큐에 남은 체결·호가를 다 쓴다.
 */
public class IngestService implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);
    private static final long STATS_INTERVAL_SECONDS = 10;

    private final TickBatchWriter<TickTrade> writer;
    private final TickBatchWriter<TickQuote> quoteWriter;
    private final SubscriptionPlanner planner;
    private final MasterRefresher master;
    private final boolean masterDownload;
    private final LocalTime masterRefreshAt;
    private final ZoneId zone;
    private final ScheduledExecutorService masterExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "master-refresh");
        t.setDaemon(true);
        return t;
    });
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
    private List<Subscription> plannedSubscriptions; // supervisor 스레드에서만
    private volatile Optional<YearMonth> frontMonth = Optional.empty();
    private Instant nextMasterRefresh; // supervisor 스레드에서만
    private volatile boolean running;

    /** 호가·마스터를 저장하지 않는 구성(테스트, Day 2 까지의 호출). */
    public IngestService(CollectorProperties p, SessionCalendar calendar, ToIntFunction<List<TickTrade>> sink,
                         OkHttpClient http, Map<String, String> env, Clock clock) {
        this(p, calendar, sink, List::size, InstrumentStore.none(), null, http, env, clock);
    }

    /**
     * @param quoteSink    호가 배치를 넣고 들어간 건수를 돌려준다
     * @param instruments  선물 종목 저장소(마스터 Upsert, 시작할 때 읽기)
     * @param masterSource 마스터 파일을 주는 곳. null 이면 설정의 주소에서 받는다
     */
    public IngestService(CollectorProperties p, SessionCalendar calendar, ToIntFunction<List<TickTrade>> sink,
                         ToIntFunction<List<TickQuote>> quoteSink, InstrumentStore instruments,
                         MasterRefresher.Source masterSource, OkHttpClient http, Map<String, String> env,
                         Clock clock) {
        ApprovalKeyProvider keys = p.approval().mode() == CollectorProperties.Approval.Mode.KIS
                ? new KisApprovalKeyClient(http, p.approval().restBaseUrl(), env, p.approval().appKeyEnv(),
                        p.approval().appSecretEnv())
                : ApprovalKeyProvider.simulator();
        TickTrade.Source source = p.source();
        this.clock = clock;
        this.gate = new SessionGate(calendar, p.session().gating(), p.session().connectLead());
        this.stalls = new StallWatch(p.stallThreshold());
        this.writer = new TickBatchWriter<>(sink, p.batch().queueCapacity(), p.batch().maxSize(),
                p.batch().flushIntervalMs());
        this.quoteWriter = new TickBatchWriter<>("quote-writer", quoteSink, p.batch().queueCapacity(),
                p.batch().maxSize(), p.batch().flushIntervalMs());
        CollectorProperties.Instruments ins = p.instrumentsOrDefault();
        this.planner = new SubscriptionPlanner(calendar, ins.mode(), p.symbols(), ins.quotes());
        CollectorProperties.Master m = ins.master();
        this.masterDownload = m != null && m.download();
        this.masterRefreshAt = m != null && m.refreshAt() != null ? m.refreshAt() : LocalTime.of(7, 30);
        MasterRefresher.Source src = masterSource;
        if (src == null && masterDownload) {
            src = new MasterDownloader(http, m.url());
        }
        this.master = new MasterRefresher(src != null ? src : () -> {
            throw new IOException("master download is off");
        }, instruments, calendar, clock);
        this.zone = calendar.zone();
        Backoff backoff = new Backoff(p.reconnect().initialBackoff(), p.reconnect().maxBackoff(),
                p.reconnect().maxJitter(), new Random());
        // 웹소켓만 ping 을 켠다. 와이파이가 끊겨도 TCP 가 닫히지 않으면 데이터가 안 오는 연결을 계속 붙어 있다고 믿게 된다
        // (2026-10-28 야간장, 와이파이를 끊었는데 SUBSCRIBED 가 유지됨). pong 이 오지 않으면 OkHttp 가 연결을 실패로 닫는다.
        OkHttpClient ws = http.newBuilder().pingInterval(p.wsPingInterval()).build();
        // 구독 목록은 처음 supervise() 가 세션 기준으로 채운다(접속보다 먼저)
        this.client = new RealtimeClient(ws, p.wsUrl(), List.of(), keys, (trade, receivedAt) -> {
            TickTrade t = TickTrade.of(trade, receivedAt, source, calendar);
            gaps.observe(t.symbol(), t.tradeDate(), t.session(), t.qty(), t.accVolume());
            stalls.onTrade(receivedAt);
            writer.offer(t);
        }, backoff, clock);
        client.setStateListener(this::onStateChange);
        client.setQuoteListener((quote, receivedAt) -> quoteWriter.offer(TickQuote.of(quote, receivedAt, source, calendar)));
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
        quoteWriter.start();
        master.loadFromStore();
        if (masterDownload) {
            masterExecutor.execute(this::refreshMaster); // 받는 동안에도 규칙·DB 코드로 바로 붙는다
            nextMasterRefresh = nextRefreshAfter(clock.instant());
        }
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

    private void refreshMaster() {
        try {
            master.refresh();
        } catch (RuntimeException e) {
            LOG.warn("master refresh failed: {}", e.toString());
        }
    }

    private Instant nextRefreshAfter(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime at = local.with(masterRefreshAt);
        if (!at.isAfter(local)) {
            at = at.plusDays(1);
        }
        return at.toInstant();
    }

    /** 구독 목록, 세션 게이트, 수신 공백, 마스터 갱신 시각 점검 한 번. 테스트는 시계를 옮긴 뒤 직접 부른다. */
    void supervise() {
        Instant now = clock.instant();
        if (masterDownload && nextMasterRefresh != null && !now.isBefore(nextMasterRefresh)) {
            nextMasterRefresh = nextRefreshAfter(now);
            masterExecutor.execute(this::refreshMaster);
        }
        planSubscriptions(now);
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

    private void planSubscriptions(Instant now) {
        FuturesContracts known = master.current();
        planner.plan(now, known).ifPresent(r -> {
            if (r.subscriptions().equals(plannedSubscriptions)) {
                return;
            }
            Optional<YearMonth> before = frontMonth;
            frontMonth = r.front();
            if (plannedSubscriptions != null && !before.equals(r.front())) {
                LOG.info("front month rolled over {} -> {}", before.orElse(null), r.front().orElse(null));
            }
            LOG.info("subscriptions for {} {} (trade date {}, front {}, codes from {}): {}", r.window().session(),
                    r.window().openDate(), r.window().tradeDate(), r.front().orElse(null), known.origin(),
                    r.subscriptions());
            plannedSubscriptions = r.subscriptions();
            client.setSubscriptions(r.subscriptions());
        });
    }

    /**
     * 로그에 남기는 한 줄 요약. trades 는 파싱한 체결, duplicates 는 이미 있어서 DB 가 버린 체결이다.
     * reconnect-* 는 끊긴 동안 놓친 체결(확실한 누락), stream-* 는 연결 중 누적거래량 빈틈(묶음 전송일 수 있다).
     */
    String summary() {
        return "state=" + client.state() + ", front=" + frontMonth.map(YearMonth::toString).orElse("-")
                + ", subs=" + client.subscriptions().size() + ", connects=" + client.connectCount() + ", disconnects="
                + client.disconnectCount() + ", recoveries=" + client.recoveryCount() + ", last-recovery-ms="
                + client.lastRecoveryMillis() + ", max-recovery-ms=" + client.maxRecoveryMillis() + ", trades="
                + client.tradeCount() + ", written=" + writer.writtenCount() + ", duplicates="
                + writer.duplicateCount() + ", dropped=" + writer.droppedCount() + ", reconnect-gaps="
                + gaps.reconnectGapCount() + ", reconnect-missing=" + gaps.reconnectMissingVolume()
                + ", reconnect-missing-rate=" + String.format(Locale.ROOT, "%.4f", gaps.reconnectMissingRate())
                + ", stream-gaps=" + gaps.streamGapCount() + ", stream-missing=" + gaps.streamMissingVolume()
                + ", repeats=" + gaps.repeatCount()
                + ", stalls=" + stalls.stallCount() + ", out-of-session-connects=" + outOfSessionConnects.get()
                + ", parse-errors=" + client.parseErrorCount() + ", subscribe-errors=" + client.subscribeErrorCount()
                + ", quotes=" + client.quoteCount() + ", quotes-written=" + quoteWriter.writtenCount()
                + ", quotes-dropped=" + quoteWriter.droppedCount();
    }

    @Override
    public void stop() {
        supervisor.shutdownNow();
        masterExecutor.shutdownNow();
        client.close();
        writer.close();
        quoteWriter.close();
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

    public TickBatchWriter<TickTrade> writer() {
        return writer;
    }

    public TickBatchWriter<TickQuote> quoteWriter() {
        return quoteWriter;
    }

    public MasterRefresher master() {
        return master;
    }

    /** 지금 근월물(AUTO 일 때). */
    public Optional<YearMonth> frontMonth() {
        return frontMonth;
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
