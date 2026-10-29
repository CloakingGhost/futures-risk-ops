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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>연결은 상태 머신으로 다룬다. 상태가 바뀔 때마다 로그 한 줄을 남긴다.
 * <pre>
 *   IDLE ──setActive(true)──▶ CONNECTING ──구독 응답(rt_cd=0) 또는 첫 체결──▶ SUBSCRIBED
 *                              │  ▲                                          │
 *                  끊김·실패   ▼  │ 대기 끝                                   │ 끊김
 *                             BACKOFF ◀──────────────────────────────────────┘
 *   (어느 상태든) ──setActive(false)·close()──▶ IDLE
 * </pre>
 * <ul>
 *   <li>CONNECTING 에 들어가면 접속키를 받고 연결을 열며, 열리면 설정된 종목을 모두 구독한다. 재접속할 때도 같은 구독을 다시 보낸다.</li>
 *   <li>BACKOFF 는 {@link Backoff}(지수 백오프 + 지터)만큼 기다린 뒤 CONNECTING 으로 간다. SUBSCRIBED 가 되면 대기 시간을 처음으로 되돌린다.</li>
 *   <li>IDLE 은 세션 밖(세션 캘린더가 끄라고 할 때)과 종료 상태다. 이때는 다시 붙지 않는다.</li>
 *   <li>SUBSCRIBED 에서 끊긴 뒤 다시 SUBSCRIBED 가 될 때까지 걸린 시간을 복구 시간으로 잰다.</li>
 *   <li>PINGPONG 메시지는 받은 텍스트를 그대로 돌려보낸다(공식 샘플과 같은 동작).</li>
 *   <li>형식이 틀린 프레임은 세고 버린다. 원문은 로그에 남기지 않는다(실제 시세를 로그로 퍼뜨리지 않으려고).</li>
 * </ul>
 * 상태는 control 스레드 하나에서만 바꾼다. 웹소켓 콜백은 일을 그 스레드로 넘기고, 연결마다 붙인 세대 번호로 지난 연결의 늦은 콜백을 무시한다.
 */
public final class RealtimeClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeClient.class);

    public enum State {
        IDLE,
        CONNECTING,
        SUBSCRIBED,
        BACKOFF
    }

    /** 수신 시각과 함께 체결을 넘겨받는 곳. 수신 스레드에서 불리므로 오래 막지 않는다. */
    @FunctionalInterface
    public interface TradeListener {
        void onTrade(H0ifcnt0Trade trade, Instant receivedAt);
    }

    /** 상태가 바뀔 때 control 스레드에서 불린다. 오래 막지 않는다. */
    @FunctionalInterface
    public interface StateListener {
        void onStateChange(State from, State to, Instant at);
    }

    private final OkHttpClient http;
    private final String url;
    private final List<String> symbols;
    private final ApprovalKeyProvider keys;
    private final TradeListener listener;
    private final Backoff backoff;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-control");
        t.setDaemon(true);
        return t;
    });
    private volatile StateListener stateListener = (from, to, at) -> { };

    // control 스레드에서만 바꾼다. 다른 스레드는 volatile 로 읽기만 한다.
    private volatile State state = State.IDLE;
    private volatile long generation;
    private boolean active;
    private WebSocket current;
    private ScheduledFuture<?> pendingConnect;
    private Instant lostAt;
    private volatile boolean closed;

    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final AtomicLong recoveries = new AtomicLong();
    private final AtomicLong lastRecoveryMs = new AtomicLong(-1);
    private final AtomicLong maxRecoveryMs = new AtomicLong(-1);
    private final AtomicLong trades = new AtomicLong();
    private final AtomicLong pingPongs = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong subscribeErrors = new AtomicLong();

    public RealtimeClient(OkHttpClient http, String url, List<String> symbols, ApprovalKeyProvider keys,
                          TradeListener listener, Backoff backoff, Clock clock) {
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required");
        }
        this.http = http;
        this.url = url;
        this.symbols = List.copyOf(symbols);
        this.keys = keys;
        this.listener = listener;
        this.backoff = backoff;
        this.clock = clock;
    }

    /** start() 전에 한 번 정한다. */
    public void setStateListener(StateListener l) {
        this.stateListener = l;
    }

    /** 세션 캘린더 없이 바로 붙는다. setActive(true) 와 같다. */
    public void start() {
        setActive(true);
    }

    /** true 면 붙어 있으려 하고(끊기면 다시 붙는다), false 면 연결을 닫고 IDLE 에서 기다린다. */
    public void setActive(boolean on) {
        run(() -> {
            if (on) {
                activate();
            } else {
                deactivate("deactivated");
            }
        });
    }

    public State state() {
        return state;
    }

    // ------------------------------------------------------------------ control 스레드

    private void run(Runnable r) {
        try {
            control.execute(r);
        } catch (RejectedExecutionException ignored) {
            // 종료 중
        }
    }

    private void transition(State to, String why) {
        State from = state;
        if (from == to) {
            return;
        }
        state = to;
        LOG.info("ws state {} -> {} ({})", from, to, why);
        try {
            stateListener.onStateChange(from, to, clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("state listener failed: {}", e.toString());
        }
    }

    private void activate() {
        if (closed || active) {
            return;
        }
        active = true;
        connectNow();
    }

    private void deactivate(String why) {
        if (!active) {
            return;
        }
        active = false;
        generation++; // 지난 연결의 늦은 콜백을 무시한다
        if (pendingConnect != null) {
            pendingConnect.cancel(false);
            pendingConnect = null;
        }
        WebSocket ws = current;
        current = null;
        lostAt = null;
        transition(State.IDLE, why);
        if (ws != null) {
            ws.close(1000, "collector: " + why);
        }
    }

    private void connectNow() {
        pendingConnect = null;
        if (!active || closed) {
            return;
        }
        long gen = ++generation;
        transition(State.CONNECTING, "connecting to " + url);
        String key;
        try {
            key = keys.approvalKey();
        } catch (RuntimeException e) {
            lost(gen, "could not get approval key: " + e.getMessage());
            return;
        }
        current = http.newWebSocket(new Request.Builder().url(url).build(), new Listener(gen, key));
    }

    private void opened(long gen) {
        if (gen != generation) {
            return;
        }
        connects.incrementAndGet();
        LOG.info("connected, sent {} subscriptions", symbols.size());
    }

    private void subscribed(long gen, String why) {
        if (gen != generation || state != State.CONNECTING) {
            return;
        }
        backoff.reset();
        transition(State.SUBSCRIBED, why);
        if (lostAt != null) {
            long ms = Duration.between(lostAt, clock.instant()).toMillis();
            recoveries.incrementAndGet();
            lastRecoveryMs.set(ms);
            maxRecoveryMs.accumulateAndGet(ms, Math::max);
            LOG.info("recovered in {} ms after disconnect", ms);
            lostAt = null;
        }
    }

    private void lost(long gen, String why) {
        if (gen != generation || !active || closed) {
            return;
        }
        if (state == State.SUBSCRIBED) {
            disconnects.incrementAndGet();
            lostAt = clock.instant();
        }
        current = null;
        Duration wait = backoff.next();
        transition(State.BACKOFF, why + "; retry in " + wait.toMillis() + " ms");
        try {
            pendingConnect = control.schedule(this::connectNow, wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 종료 중
        }
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

    // ------------------------------------------------------------------ 웹소켓 콜백(OkHttp 스레드)

    private final class Listener extends WebSocketListener {
        private final long gen;
        private final String approvalKey;

        Listener(long gen, String approvalKey) {
            this.gen = gen;
            this.approvalKey = approvalKey;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            if (gen != generation) {
                ws.close(1000, "stale connection");
                return;
            }
            for (String s : symbols) {
                ws.send(subscribeMessage(approvalKey, s));
            }
            run(() -> opened(gen));
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
            if (state == State.CONNECTING) {
                run(() -> subscribed(gen, "first trade received"));
            }
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
                run(() -> subscribed(gen, "subscription accepted for " + trKey));
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
            run(() -> lost(gen, "connection closed (" + code + " " + reason + ")"));
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            run(() -> lost(gen, "connection failed: " + t));
        }
    }

    @Override
    public void close() {
        try {
            control.submit(() -> deactivate("collector shutdown")).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            LOG.warn("could not close cleanly: {}", e.toString());
        }
        closed = true;
        control.shutdownNow();
    }

    public long connectCount() {
        return connects.get();
    }

    /** SUBSCRIBED 상태에서 끊긴 횟수. */
    public long disconnectCount() {
        return disconnects.get();
    }

    /** 끊긴 뒤 다시 SUBSCRIBED 가 된 횟수. */
    public long recoveryCount() {
        return recoveries.get();
    }

    /** 마지막 복구에 걸린 시간(ms). 복구한 적이 없으면 -1. */
    public long lastRecoveryMillis() {
        return lastRecoveryMs.get();
    }

    /** 가장 오래 걸린 복구 시간(ms). 복구한 적이 없으면 -1. */
    public long maxRecoveryMillis() {
        return maxRecoveryMs.get();
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
