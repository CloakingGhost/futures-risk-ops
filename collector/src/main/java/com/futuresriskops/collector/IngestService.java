package com.futuresriskops.collector;

import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.KisApprovalKeyClient;
import com.futuresriskops.collector.ws.RealtimeClient;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 웹소켓 수신 → 파싱 → 배치 적재를 묶어 스프링 시작·종료에 맞춰 켜고 끈다. 끌 때는 큐에 남은 체결을 다 쓴다. */
public class IngestService implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);
    private static final long STATS_INTERVAL_SECONDS = 10;

    private final TickBatchWriter writer;
    private final RealtimeClient client;
    private final ScheduledExecutorService statsLogger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ingest-stats");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    public IngestService(CollectorProperties p, TickTradeRepository repository, OkHttpClient http,
                         Map<String, String> env, Clock clock) {
        ApprovalKeyProvider keys = p.approval().mode() == CollectorProperties.Approval.Mode.KIS
                ? new KisApprovalKeyClient(http, p.approval().restBaseUrl(), env, p.approval().appKeyEnv(),
                        p.approval().appSecretEnv())
                : ApprovalKeyProvider.simulator();
        TickTrade.Source source = p.source();
        this.writer = new TickBatchWriter(repository::insertBatch, p.batch().queueCapacity(), p.batch().maxSize(),
                p.batch().flushIntervalMs());
        this.client = new RealtimeClient(http, p.wsUrl(), p.symbols(), keys,
                (trade, receivedAt) -> writer.offer(TickTrade.of(trade, receivedAt, source)),
                p.maxReconnectBackoff(), clock);
    }

    @Override
    public void start() {
        writer.start();
        client.start();
        // Windows 에서 Ctrl+C 로 gradle bootRun 을 끄면 종료 훅이 돌지 않아 마지막 요약이 안 남는다. 그래서 주기적으로도 남긴다.
        statsLogger.scheduleAtFixedRate(() -> LOG.info("ingest stats: {}", summary(client, writer)), STATS_INTERVAL_SECONDS,
                STATS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        running = true;
    }

    /** 로그에 남기는 한 줄 요약. trades 는 파싱한 체결, duplicates 는 이미 있어서 DB 가 버린 체결이다. */
    static String summary(RealtimeClient client, TickBatchWriter writer) {
        return "connects=" + client.connectCount() + ", trades=" + client.tradeCount() + ", written="
                + writer.writtenCount() + ", duplicates=" + writer.duplicateCount() + ", dropped="
                + writer.droppedCount() + ", parse-errors=" + client.parseErrorCount() + ", subscribe-errors="
                + client.subscribeErrorCount();
    }

    @Override
    public void stop() {
        statsLogger.shutdownNow();
        client.close();
        writer.close();
        running = false;
        LOG.info("ingest stopped: {}", summary(client, writer));
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
}
