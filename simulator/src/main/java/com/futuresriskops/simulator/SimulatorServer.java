package com.futuresriskops.simulator;

import com.futuresriskops.simulator.config.SimulatorConfig;
import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.KisFrames;
import com.futuresriskops.simulator.market.PriceModel;
import com.futuresriskops.simulator.ws.MiniJson;
import com.futuresriskops.simulator.ws.WebSocketConnection;
import com.futuresriskops.simulator.ws.WebSocketServer;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H0IFCNT0 형식의 가짜 체결을 웹소켓으로 내보내는 서버.
 *
 * <p>클라이언트는 KIS 와 같은 JSON 구독 요청을 보낸다(approval_key 는 검사하지 않는다).
 * 종목 코드는 아무 문자열이나 받고, 처음 구독될 때 그 종목의 가짜 시세 피드를 만든다.
 * 주문·계좌·실제 인증 기능은 없다.
 */
public final class SimulatorServer implements WebSocketServer.Listener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SimulatorServer.class.getName());
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SimulatorConfig cfg;
    private final Clock clock;
    private final WebSocketServer ws;
    private final Map<WebSocketConnection, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SymbolFeed> feeds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService feedExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "feed");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong sessionSeq = new AtomicLong();

    public SimulatorServer(SimulatorConfig cfg) {
        this(cfg, Clock.system(KST));
    }

    public SimulatorServer(SimulatorConfig cfg, Clock clock) {
        this.cfg = cfg;
        this.clock = clock;
        this.ws = new WebSocketServer(cfg.server().host(), cfg.server().port(), this);
    }

    public void start() throws IOException {
        ws.start();
    }

    public int port() {
        return ws.port();
    }

    public int sessionCount() {
        return sessions.size();
    }

    // ------------------------------------------------------------------ 웹소켓 콜백

    @Override
    public void onOpen(WebSocketConnection conn) {
        long id = sessionSeq.incrementAndGet();
        long seed = cfg.price().seed().isPresent() ? cfg.price().seed().getAsLong() + id : System.nanoTime();
        ClientSession s = new ClientSession(conn, cfg, clock, seed);
        sessions.put(conn, s);
        s.start();
        LOG.log(System.Logger.Level.INFO, "client connected {0}", conn.remote());
    }

    @Override
    public void onPong(WebSocketConnection conn, byte[] payload) {
        ClientSession s = sessions.get(conn);
        if (s != null) {
            s.onPong();
        }
    }

    @Override
    public void onClose(WebSocketConnection conn) {
        ClientSession s = sessions.remove(conn);
        if (s == null) {
            return;
        }
        for (SymbolFeed f : feeds.values()) {
            f.subscribers.remove(s);
        }
        s.shutdown();
        LOG.log(System.Logger.Level.INFO, "client disconnected {0} (sent={1}, stalled-drops={2}, pongs={3})",
                conn.remote(), s.sentCount(), s.stalledDropCount(), s.pongCount());
    }

    @Override
    public void onText(WebSocketConnection conn, String text) {
        ClientSession s = sessions.get(conn);
        if (s == null) {
            return;
        }
        Object json;
        try {
            json = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            conn.sendText(KisFrames.error("", "", "malformed request: " + e.getMessage()));
            return;
        }
        String trType = MiniJson.getString(json, "header", "tr_type");
        String trId = MiniJson.getString(json, "body", "input", "tr_id");
        String trKey = MiniJson.getString(json, "body", "input", "tr_key");

        if (trType == null || trId == null || trKey == null || trKey.isBlank()) {
            conn.sendText(KisFrames.error(trId, trKey, "header.tr_type, body.input.tr_id and body.input.tr_key are required"));
            return;
        }
        if (!trId.equals(H0IFCNT0.TR_ID)) {
            conn.sendText(KisFrames.error(trId, trKey, "this simulator only serves " + H0IFCNT0.TR_ID));
            return;
        }
        switch (trType) {
            case "1" -> subscribe(s, conn, trId, trKey.trim());
            case "2" -> unsubscribe(s, conn, trId, trKey.trim());
            default -> conn.sendText(KisFrames.error(trId, trKey, "tr_type must be \"1\" (subscribe) or \"2\" (unsubscribe)"));
        }
    }

    private void subscribe(ClientSession s, WebSocketConnection conn, String trId, String symbol) {
        if (!s.subscriptions().contains(symbol) && s.subscriptions().size() >= cfg.server().maxSubscriptions()) {
            conn.sendText(KisFrames.error(trId, symbol,
                    "max subscriptions per connection is " + cfg.server().maxSubscriptions()));
            return;
        }
        // 응답을 먼저 보낸 뒤 피드에 붙인다: 응답보다 체결이 먼저 가지 않게
        conn.sendText(KisFrames.ok(trId, symbol, KisFrames.MSG_SUBSCRIBE_OK));
        if (s.subscriptions().add(symbol)) {
            feeds.computeIfAbsent(symbol, this::startFeed).subscribers.add(s);
            LOG.log(System.Logger.Level.INFO, "subscribed {0} to {1}", conn.remote(), symbol);
        }
    }

    private void unsubscribe(ClientSession s, WebSocketConnection conn, String trId, String symbol) {
        if (s.subscriptions().remove(symbol)) {
            SymbolFeed f = feeds.get(symbol);
            if (f != null) {
                f.subscribers.remove(s);
            }
        }
        conn.sendText(KisFrames.ok(trId, symbol, KisFrames.MSG_UNSUBSCRIBE_OK));
    }

    // ------------------------------------------------------------------ 종목별 피드

    private SymbolFeed startFeed(String symbol) {
        SymbolFeed feed = new SymbolFeed(symbol);
        feed.scheduleNext(System.nanoTime());
        return feed;
    }

    /** 종목 하나의 체결을 설정된 속도로 만들어 구독자에게 넘긴다. */
    private final class SymbolFeed {
        final String symbol;
        final PriceModel model;
        final CopyOnWriteArraySet<ClientSession> subscribers = new CopyOnWriteArraySet<>();
        private final Random rng;
        private volatile boolean stopped;

        SymbolFeed(String symbol) {
            this.symbol = symbol;
            this.model = new PriceModel(symbol, cfg.price());
            long seed = cfg.price().seed().isPresent() ? cfg.price().seed().getAsLong() ^ ~symbol.hashCode() : System.nanoTime();
            this.rng = new Random(seed);
        }

        /** 이상적인 다음 체결 시각을 기준으로 예약해서 평균 속도가 밀리지 않게 한다. */
        void scheduleNext(long dueNanos) {
            if (stopped) {
                return;
            }
            long wait = Math.max(0, dueNanos - System.nanoTime());
            try {
                feedExecutor.schedule(() -> tick(dueNanos), wait, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException ignored) {
                // 서버 종료 중
            }
        }

        private void tick(long dueNanos) {
            try {
                if (!subscribers.isEmpty()) {
                    String frame = model.next(LocalTime.now(clock)).toFrame();
                    for (ClientSession s : subscribers) {
                        s.deliver(frame);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "tick failed for " + symbol, e);
            } finally {
                double mean = 1e9 / cfg.rate().ticksPerSecond();
                double jitter = cfg.rate().jitter();
                long interval = (long) (mean * (1 + jitter * (2 * rng.nextDouble() - 1)));
                scheduleNext(dueNanos + Math.max(1, interval));
            }
        }

        void stop() {
            stopped = true;
        }
    }

    @Override
    public void close() {
        feeds.values().forEach(SymbolFeed::stop);
        feedExecutor.shutdownNow();
        ws.close();
        sessions.values().forEach(ClientSession::shutdown);
    }
}
