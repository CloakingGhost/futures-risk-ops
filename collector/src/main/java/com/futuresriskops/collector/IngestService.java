package com.futuresriskops.collector;

import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.KisApprovalKeyClient;
import com.futuresriskops.collector.ws.RealtimeClient;
import java.time.Clock;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 웹소켓 수신 → 파싱 → 배치 적재를 묶어 스프링 시작·종료에 맞춰 켜고 끈다. 끌 때는 큐에 남은 체결을 다 쓴다. */
public class IngestService implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);

    private final TickBatchWriter writer;
    private final RealtimeClient client;
    private volatile boolean running;

    public IngestService(CollectorProperties p, TickTradeRepository repository, OkHttpClient http,
                         Map<String, String> env, Clock clock) {
        ApprovalKeyProvider keys = p.approval().mode() == CollectorProperties.Approval.Mode.KIS
                ? new KisApprovalKeyClient(http, p.approval().restBaseUrl(), env)
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
        running = true;
    }

    @Override
    public void stop() {
        client.close();
        writer.close();
        running = false;
        LOG.info("ingest stopped: trades={}, written={}, duplicates={}, dropped={}, parse-errors={}",
                client.tradeCount(), writer.writtenCount(), writer.duplicateCount(), writer.droppedCount(),
                client.parseErrorCount());
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
