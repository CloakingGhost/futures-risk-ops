package com.futuresriskops.collector.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.futuresriskops.collector.kis.H0ifcnt0Parser;
import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIS 실시간 웹소켓 클라이언트(H0IFCNT0 체결). 시뮬레이터와 KIS 에 같은 코드로 붙고, 다른 것은 접속 주소와 접속키뿐이다.
 *
 * <ul>
 *   <li>연결되면 설정된 종목을 모두 구독한다. 재접속할 때도 같은 구독을 다시 보낸다.</li>
 *   <li>PINGPONG 메시지는 받은 텍스트를 그대로 돌려보낸다(공식 샘플과 같은 동작).</li>
 *   <li>끊기면 1초부터 두 배씩 늘려 최대 {@code maxBackoff} 간격으로 다시 붙는다. 붙으면 간격을 처음으로 되돌린다.</li>
 *   <li>형식이 틀린 프레임은 세고 버린다. 원문은 로그에 남기지 않는다(실제 시세를 로그로 퍼뜨리지 않으려고).</li>
 * </ul>
 */
public final class RealtimeClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeClient.class);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    /** 수신 시각과 함께 체결을 넘겨받는 곳. 수신 스레드에서 불리므로 오래 막지 않는다. */
    @FunctionalInterface
    public interface TradeListener {
        void onTrade(H0ifcnt0Trade trade, Instant receivedAt);
    }

    private final OkHttpClient http;
    private final String url;
    private final List<String> symbols;
    private final ApprovalKeyProvider keys;
    private final TradeListener listener;
    private final Duration maxBackoff;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean closed;
    private volatile WebSocket current;
    private Duration backoff = INITIAL_BACKOFF; // reconnector 스레드에서만 바꾼다

    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong trades = new AtomicLong();
    private final AtomicLong pingPongs = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong subscribeErrors = new AtomicLong();

    public RealtimeClient(OkHttpClient http, String url, List<String> symbols, ApprovalKeyProvider keys,
                          TradeListener listener, Duration maxBackoff, Clock clock) {
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required");
        }
        this.http = http;
        this.url = url;
        this.symbols = List.copyOf(symbols);
        this.keys = keys;
        this.listener = listener;
        this.maxBackoff = maxBackoff;
        this.clock = clock;
    }

    public void start() {
        schedule(Duration.ZERO);
    }

    private void schedule(Duration delay) {
        if (closed) {
            return;
        }
        try {
            reconnector.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 종료 중
        }
    }

    private void connect() {
        if (closed) {
            return;
        }
        String key;
        try {
            key = keys.approvalKey();
        } catch (RuntimeException e) {
            LOG.warn("could not get approval key: {}", e.getMessage());
            retryLater();
            return;
        }
        LOG.info("connecting to {}", url);
        current = http.newWebSocket(new Request.Builder().url(url).build(), new Listener(key));
    }

    private void retryLater() {
        Duration wait = backoff;
        backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
        LOG.info("reconnecting in {} ms", wait.toMillis());
        schedule(wait);
    }

    private String subscribeMessage(String approvalKey, String symbol) {
        ObjectNode root = json.createObjectNode();
        ObjectNode header = root.putObject("header");
        header.put("approval_key", approvalKey);
        header.put("custtype", "P");
        header.put("tr_type", "1");
        header.put("content-type", "utf-8");
        ObjectNode input = root.putObject("body").putObject("input");
        input.put("tr_id", H0ifcnt0Parser.TR_ID);
        input.put("tr_key", symbol);
        return root.toString();
    }

    private final class Listener extends WebSocketListener {
        private final String approvalKey;

        Listener(String approvalKey) {
            this.approvalKey = approvalKey;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            connects.incrementAndGet();
            reconnector.execute(() -> backoff = INITIAL_BACKOFF);
            for (String s : symbols) {
                ws.send(subscribeMessage(approvalKey, s));
            }
            LOG.info("connected, subscribed {} symbols", symbols.size());
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Instant receivedAt = clock.instant();
            if (H0ifcnt0Parser.isDataFrame(text)) {
                onData(text, receivedAt);
            } else {
                onSystem(ws, text);
            }
        }

        private void onData(String text, Instant receivedAt) {
            List<H0ifcnt0Trade> parsed;
            try {
                parsed = H0ifcnt0Parser.parse(text);
            } catch (IllegalArgumentException e) {
                long n = parseErrors.incrementAndGet();
                if (n <= 10 || n % 1000 == 0) {
                    LOG.warn("dropped malformed frame #{}: {}", n, e.getMessage());
                }
                return;
            }
            for (H0ifcnt0Trade t : parsed) {
                trades.incrementAndGet();
                listener.onTrade(t, receivedAt);
            }
        }

        private void onSystem(WebSocket ws, String text) {
            JsonNode node;
            try {
                node = json.readTree(text);
            } catch (IOException e) {
                parseErrors.incrementAndGet();
                LOG.warn("dropped a message that is neither a data frame nor JSON");
                return;
            }
            String trId = node.path("header").path("tr_id").asText("");
            if ("PINGPONG".equals(trId)) {
                ws.send(text);
                pingPongs.incrementAndGet();
                return;
            }
            String rtCd = node.path("body").path("rt_cd").asText("");
            String trKey = node.path("header").path("tr_key").asText("");
            String msg = node.path("body").path("msg1").asText("");
            if ("0".equals(rtCd)) {
                LOG.info("{} {}: {}", trId, trKey, msg);
            } else {
                subscribeErrors.incrementAndGet();
                LOG.warn("{} {} rejected (rt_cd={}, msg_cd={}): {}", trId, trKey, rtCd,
                        node.path("body").path("msg_cd").asText(""), msg);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            LOG.info("connection closed ({} {})", code, reason);
            lost(ws);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            LOG.warn("connection failed: {}", t.toString());
            lost(ws);
        }

        private void lost(WebSocket ws) {
            if (closed || ws != current) {
                return;
            }
            reconnector.execute(RealtimeClient.this::retryLater);
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = current;
        if (ws != null) {
            ws.close(1000, "collector shutdown");
        }
        reconnector.shutdownNow();
    }

    public long connectCount() {
        return connects.get();
    }

    public long tradeCount() {
        return trades.get();
    }

    public long pingPongCount() {
        return pingPongs.get();
    }

    public long parseErrorCount() {
        return parseErrors.get();
    }

    public long subscribeErrorCount() {
        return subscribeErrors.get();
    }
}
