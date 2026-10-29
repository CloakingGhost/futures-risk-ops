package com.futuresriskops.collector;

import com.futuresriskops.collector.kis.H0ifasp0Parser;
import com.futuresriskops.collector.kis.H0ifcnt0Parser;
import com.futuresriskops.collector.monitor.GapDetector;
import com.futuresriskops.collector.monitor.StallWatch;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.ws.RealtimeClient;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 수집기 지표 9종을 레지스트리에 붙인다. 이름과 뜻은 docs/metrics.md 에 고정하고, 바꿀 때는 그 문서와 알람·대시보드를 같이 고친다.
 *
 * <p>카운터는 새로 세지 않고 기존 카운터(로그 {@code ingest stats} 의 숫자)를 읽는다. 지연 두 가지만 여기서 잰다.
 * Prometheus 이름은 점이 밑줄로 바뀌고, 카운터에는 {@code _total}, 타이머에는 {@code _seconds} 가 붙는다.
 * 지연 기록 훅을 IngestService 에 거므로 레지스트리 하나에만 붙인다.
 */
public final class CollectorMetrics implements MeterBinder {

    static final String MESSAGES_RECEIVED = "collector.messages.received";
    static final String TRADE_TO_RECEIVE = "collector.trade.to.receive";
    static final String RECEIVE_TO_COMMIT = "collector.receive.to.commit";
    static final String WS_STATE = "collector.ws.state";
    static final String WS_RECONNECTS = "collector.ws.reconnects";
    static final String MISSING_VOLUME = "collector.missing.volume";
    static final String MARKET_SESSION_OPEN = "collector.market.session.open";
    static final String TOKEN_EXPIRY = "collector.token.expiry.timestamp.seconds";
    static final String LAST_TRADE_RECEIVED = "collector.last.trade.received.timestamp.seconds";

    /** (kind, tr_id). 처음부터 0 으로 내보내야 알람식이 '값 없음' 을 따로 다루지 않는다. */
    private static final List<String[]> RECEIVED_SERIES = List.of(
            new String[] {"trade", H0ifcnt0Parser.TR_ID},
            new String[] {"trade", H0ifcnt0Parser.NIGHT_TR_ID},
            new String[] {"quote", H0ifasp0Parser.TR_ID});

    // bsop_hour 가 초 단위라 체결→수신은 0~1초 부풀려진다. 그래서 1초 아래 칸을 잘게 나누지 않는다
    private static final Duration[] TRADE_TO_RECEIVE_BUCKETS = seconds(0.5, 1, 1.5, 2, 3, 5, 10, 30, 60);
    private static final Duration[] RECEIVE_TO_COMMIT_BUCKETS = seconds(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5,
            5, 10);

    private final IngestService ingest;

    public CollectorMetrics(IngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        RealtimeClient client = ingest.client();
        GapDetector gaps = ingest.gaps();
        StallWatch stalls = ingest.stalls();

        for (String[] kt : RECEIVED_SERIES) {
            String trId = kt[1];
            FunctionCounter.builder(MESSAGES_RECEIVED, client, c -> c.receivedCount(trId))
                    .tags("kind", kt[0], "tr_id", trId)
                    .description("Trades and quotes received and parsed, per TR")
                    .register(registry);
        }

        Timer tradeToReceive = Timer.builder(TRADE_TO_RECEIVE)
                .description("Trade time (bsop_hour, whole seconds) to receive time")
                .serviceLevelObjectives(TRADE_TO_RECEIVE_BUCKETS)
                .register(registry);
        Timer receiveToCommit = Timer.builder(RECEIVE_TO_COMMIT)
                .description("Receive time to the end of the tick_trade batch insert")
                .serviceLevelObjectives(RECEIVE_TO_COMMIT_BUCKETS)
                .register(registry);
        Clock clock = ingest.clock();
        ingest.setTradeObserver(t -> tradeToReceive.record(nonNegative(t.tradedAt(), t.receivedAt())));
        ingest.writer().setCommitListener(batch -> {
            Instant committedAt = clock.instant();
            for (TickTrade t : batch) {
                receiveToCommit.record(nonNegative(t.receivedAt(), committedAt));
            }
        });

        for (RealtimeClient.State s : RealtimeClient.State.values()) {
            Gauge.builder(WS_STATE, client, c -> c.state() == s ? 1 : 0)
                    .tag("state", s.name())
                    .description("1 for the current WebSocket state, 0 for the others")
                    .register(registry);
        }
        FunctionCounter.builder(WS_RECONNECTS, client, RealtimeClient::recoveryCount)
                .description("Times the connection was subscribed again after a drop")
                .register(registry);
        FunctionCounter.builder(MISSING_VOLUME, gaps, GapDetector::reconnectMissingVolume)
                .tag("kind", "reconnect")
                .description("Contracts missed while disconnected (accumulated volume jump)")
                .register(registry);
        FunctionCounter.builder(MISSING_VOLUME, gaps, GapDetector::streamMissingVolume)
                .tag("kind", "stream")
                .description("Accumulated volume gaps while connected (may be batched sending)")
                .register(registry);

        Gauge.builder(MARKET_SESSION_OPEN, ingest, i -> i.marketSessionOpen() ? 1 : 0)
                .description("1 while the session calendar says the market is open")
                .register(registry);
        Gauge.builder(TOKEN_EXPIRY, ingest,
                        i -> i.approvalKeyExpiresAt().map(CollectorMetrics::epochSeconds).orElse(0.0))
                .description("Assumed expiry of the WebSocket approval key; 0 if none (simulator)")
                .register(registry);
        Gauge.builder(LAST_TRADE_RECEIVED, stalls, w -> {
                    Instant at = w.lastTradeAt();
                    return at != null ? epochSeconds(at) : 0.0;
                })
                .description("Receive time of the last trade; 0 if none yet")
                .register(registry);
    }

    /** 시계가 어긋나 음수가 나오면 0 으로 센다(Timer 는 음수를 받지 않는다). */
    private static Duration nonNegative(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return d.isNegative() ? Duration.ZERO : d;
    }

    private static double epochSeconds(Instant t) {
        return t.getEpochSecond() + t.getNano() / 1e9;
    }

    private static Duration[] seconds(double... values) {
        Duration[] out = new Duration[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Duration.ofNanos(Math.round(values[i] * 1e9));
        }
        return out;
    }
}
