package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.kis.H0ifasp0Quote;
import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 시뮬레이터를 같은 프로세스에 띄우고 실제 소켓으로 붙는다. 키는 필요 없다. */
class RealtimeClientTest {

    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<H0ifcnt0Trade> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    private SimulatorServer simulator(String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--price.seed=7",
                "--rate.ticks-per-second=50", "--pingpong.interval-seconds=0"));
        args.addAll(List.of(extra));
        SimulatorServer s = new SimulatorServer(SimulatorConfig.load(args.toArray(String[]::new), Map.of()));
        s.start();
        open.add(s);
        return s;
    }

    private RealtimeClient client(SimulatorServer s, String... symbols) {
        RealtimeClient c = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:" + s.port() + "/tryitout",
                RealtimeClient.dayTrades(List.of(symbols)), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
                new Backoff(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ZERO, new Random(1)),
                Clock.systemUTC());
        open.add(c);
        c.start();
        return c;
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
    void subscribesAllSymbolsAndParsesTrades() throws Exception {
        SimulatorServer s = simulator();
        RealtimeClient c = client(s, "A01612", "A01703");
        await(() -> received.stream().map(H0ifcnt0Trade::symbol).distinct().count() == 2, "both symbols");
        assertEquals(0, c.parseErrorCount());
        assertEquals(0, c.subscribeErrorCount());
    }

    @Test
    void echoesPingPongBackToTheServer() throws Exception {
        SimulatorServer s = simulator("--pingpong.interval-seconds=0.2");
        RealtimeClient c = client(s, "A01612");
        await(() -> s.pingPongEchoCount() >= 2, "two echoed PINGPONGs");
        assertTrue(c.pingPongCount() >= 2);
    }

    @Test
    void reconnectsAndSubscribesAgainAfterTheServerDrops() throws Exception {
        SimulatorServer s = simulator("--disconnect.after-seconds=0.5");
        RealtimeClient c = client(s, "A01612");
        await(() -> c.connectCount() >= 3, "three connections");
        int before = received.size();
        await(() -> received.size() > before, "trades after reconnect");
    }

    @Test
    void reconnectsAfterAnAbruptReset() throws Exception {
        SimulatorServer s = simulator("--disconnect.after-seconds=0.5", "--disconnect.abrupt=true");
        RealtimeClient c = client(s, "A01612");
        await(() -> c.connectCount() >= 2, "a second connection");
    }

    @Test
    void countsRejectedSubscriptions() throws Exception {
        SimulatorServer s = simulator("--server.max-subscriptions=1");
        RealtimeClient c = client(s, "A01612", "A01703");
        await(() -> c.subscribeErrorCount() == 1, "one rejected subscription");
        Set<String> symbols = ConcurrentHashMap.newKeySet();
        await(() -> {
            received.forEach(t -> symbols.add(t.symbol()));
            return !symbols.isEmpty();
        }, "trades for the accepted symbol");
        assertEquals(Set.of("A01612"), symbols);
    }

    @Test
    void reachesSubscribedAndMeasuresRecoveryAfterEachDrop() throws Exception {
        SimulatorServer s = simulator("--disconnect.after-seconds=0.5");
        List<RealtimeClient.State> states = new CopyOnWriteArrayList<>();
        RealtimeClient c = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:" + s.port() + "/tryitout",
                RealtimeClient.dayTrades(List.of("A01612")), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
                new Backoff(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMillis(50), new Random(1)),
                Clock.systemUTC());
        open.add(c);
        c.setStateListener((from, to, at) -> states.add(to));
        c.start();
        await(() -> c.recoveryCount() >= 3, "three recoveries");
        assertTrue(c.disconnectCount() >= 3);
        assertTrue(c.maxRecoveryMillis() >= 0 && c.maxRecoveryMillis() < 30_000, "recovery within 30s");
        // CONNECTING → SUBSCRIBED → BACKOFF → CONNECTING → SUBSCRIBED ...
        assertEquals(List.of(RealtimeClient.State.CONNECTING, RealtimeClient.State.SUBSCRIBED,
                RealtimeClient.State.BACKOFF, RealtimeClient.State.CONNECTING, RealtimeClient.State.SUBSCRIBED),
                states.subList(0, 5));
    }

    @Test
    void deactivatedClientClosesAndDoesNotReconnectUntilActivatedAgain() throws Exception {
        SimulatorServer s = simulator();
        RealtimeClient c = client(s, "A01612");
        await(() -> c.state() == RealtimeClient.State.SUBSCRIBED, "subscribed");
        c.setActive(false);
        await(() -> c.state() == RealtimeClient.State.IDLE, "idle");
        await(() -> s.sessionCount() == 0, "server sees the connection closed");
        Thread.sleep(500);
        assertEquals(1, c.connectCount(), "no reconnect while inactive");
        assertEquals(0, c.disconnectCount(), "a requested close is not a disconnect");
        c.setActive(true);
        await(() -> c.state() == RealtimeClient.State.SUBSCRIBED, "subscribed again");
        assertEquals(2, c.connectCount());
    }

    @Test
    void retriesWithBackoffWhileTheServerIsDown() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort(); // 닫힌 포트: 아무도 받지 않는다
        }
        List<RealtimeClient.State> states = new CopyOnWriteArrayList<>();
        RealtimeClient c = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:" + port + "/tryitout",
                RealtimeClient.dayTrades(List.of("A01612")), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
                new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), Duration.ZERO, new Random(1)),
                Clock.systemUTC());
        open.add(c);
        c.setStateListener((from, to, at) -> states.add(to));
        c.start();
        await(() -> states.stream().filter(x -> x == RealtimeClient.State.BACKOFF).count() >= 3, "three backoffs");
        assertEquals(0, c.connectCount());
        assertEquals(-1, c.lastRecoveryMillis(), "never subscribed, so nothing to recover");
    }

    /**
     * 와이파이가 끊긴 것처럼: 핸드셰이크와 구독 응답까지 한 뒤 TCP 는 열어 둔 채 아무것도 보내지도 답하지도 않는 서버.
     * ping 을 켜 두면 pong 이 오지 않아 클라이언트가 죽은 연결로 보고 끊어야 한다.
     */
    /** 연결 하나를 받아 웹소켓 핸드셰이크를 하고 텍스트 프레임 하나(125 바이트 이하)를 보낸다. 그 뒤로는 읽지도 쓰지도 않는다. */
    private void acceptAndSend(ServerSocket server, String text) throws Exception {
        Socket s = server.accept();
        open.add(s);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        String key = null;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        OutputStream out = s.getOutputStream();
        String crlf = "\r\n";
        out.write(("HTTP/1.1 101 Switching Protocols" + crlf + "Upgrade: websocket" + crlf
                + "Connection: Upgrade" + crlf + "Sec-WebSocket-Accept: " + accept + crlf + crlf)
                .getBytes(StandardCharsets.ISO_8859_1));
        byte[] frame = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81); // FIN + 텍스트 프레임
        out.write(frame.length); // 125 바이트 이하
        out.write(frame);
        out.flush();
    }

    @Test
    void pingWithoutPongMarksASilentlyDeadConnectionAsLost() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try {
                    acceptAndSend(server, "{\"header\":{\"tr_id\":\"H0IFCNT0\",\"tr_key\":\"A01612\"},"
                            + "\"body\":{\"rt_cd\":\"0\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}");
                    // 이후로는 읽지도 쓰지도 않는다(ping 에 pong 이 없다)
                } catch (Exception ignored) {
                    // 테스트 종료로 닫힘
                }
            }, "silent-server");
            t.setDaemon(true);
            t.start();

            OkHttpClient http = new OkHttpClient.Builder().pingInterval(Duration.ofMillis(200)).build();
            List<RealtimeClient.State> states = new CopyOnWriteArrayList<>();
            RealtimeClient c = new RealtimeClient(http, "ws://127.0.0.1:" + server.getLocalPort() + "/tryitout",
                    RealtimeClient.dayTrades(List.of("A01612")), ApprovalKeyProvider.simulator(), (tr, at) -> received.add(tr),
                    new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ZERO, new Random(1)),
                    Clock.systemUTC());
            open.add(c);
            c.setStateListener((from, to, at) -> states.add(to));
            c.start();
            await(() -> states.contains(RealtimeClient.State.SUBSCRIBED), "subscribed");
            await(() -> c.state() == RealtimeClient.State.BACKOFF, "the dead connection is noticed");
            assertEquals(1, c.disconnectCount());
        }
    }

    @Test
    void onlyApprovalMessagesCountAsARejectedKey() {
        assertTrue(RealtimeClient.approvalRejected("invalid approval : NOT FOUND"));
        assertTrue(RealtimeClient.approvalRejected("Invalid Approval Key"));
        assertFalse(RealtimeClient.approvalRejected("MAX SUBSCRIBE OVER"));
        assertFalse(RealtimeClient.approvalRejected(""));
        assertFalse(RealtimeClient.approvalRejected(null));
    }

    @Test
    void aRejectedApprovalKeyIsDroppedAndTheClientReconnectsWithANewOne() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        acceptAndSend(server, "{\"header\":{\"tr_id\":\"H0IFCNT0\",\"tr_key\":\"A01612\"},"
                                + "\"body\":{\"rt_cd\":\"1\",\"msg1\":\"invalid approval : NOT FOUND\"}}");
                    }
                } catch (Exception ignored) {
                    // 테스트 종료로 닫힘
                }
            }, "rejecting-server");
            t.setDaemon(true);
            t.start();

            AtomicInteger issued = new AtomicInteger();
            AtomicInteger invalidated = new AtomicInteger();
            ApprovalKeyProvider keys = new ApprovalKeyProvider() {
                @Override
                public String approvalKey() {
                    return "test-key-" + issued.incrementAndGet();
                }

                @Override
                public void invalidate() {
                    invalidated.incrementAndGet();
                }
            };
            RealtimeClient c = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:" + server.getLocalPort() + "/tryitout",
                    RealtimeClient.dayTrades(List.of("A01612")), keys, (tr, at) -> received.add(tr),
                    new Backoff(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ZERO, new Random(1)),
                    Clock.systemUTC());
            open.add(c);
            c.start();
            await(() -> c.connectCount() >= 2, "a second connection");
            assertTrue(invalidated.get() >= 1, "the rejected key was dropped");
            assertTrue(issued.get() >= 2, "the reconnect asked for a key again");
            assertTrue(c.subscribeErrorCount() >= 1);
        }
    }

    @Test
    void changingSubscriptionsWhileConnectedSwapsSymbolsWithoutReconnecting() throws Exception {
        SimulatorServer s = simulator();
        RealtimeClient c = client(s, "A01612");
        await(() -> received.stream().anyMatch(t -> t.symbol().equals("A01612")), "front month trades");
        c.setSubscriptions(List.of(new Subscription("H0IFCNT0", "A01612"), new Subscription("H0IFCNT0", "A01703")));
        await(() -> received.stream().anyMatch(t -> t.symbol().equals("A01703")), "next month trades during overlap");
        c.setSubscriptions(List.of(new Subscription("H0MFCNT0", "A01703")));
        Thread.sleep(300); // 해제 응답과 이미 보낸 체결이 지나가길 기다린다
        received.clear();
        await(() -> received.size() >= 5, "night trades of the next month");
        assertTrue(received.stream().allMatch(t -> t.symbol().equals("A01703")), "the old month is unsubscribed");
        assertEquals(1, c.connectCount(), "no reconnect for a subscription change");
        assertEquals(2, c.subscriptionChangeCount());
        assertEquals(0, c.parseErrorCount());
    }

    @Test
    void quotesGoToTheQuoteListener() throws Exception {
        SimulatorServer s = simulator();
        List<H0ifasp0Quote> quotes = new CopyOnWriteArrayList<>();
        RealtimeClient c = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:" + s.port() + "/tryitout",
                List.of(new Subscription("H0IFCNT0", "A01612"), new Subscription("H0IFASP0", "A01612")),
                ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
                new Backoff(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ZERO, new Random(1)),
                Clock.systemUTC());
        c.setQuoteListener((q, at) -> quotes.add(q));
        open.add(c);
        c.start();
        await(() -> quotes.size() >= 5 && received.size() >= 5, "quotes and trades");
        assertEquals(quotes.size(), c.quoteCount());
        assertEquals(0, c.parseErrorCount());
    }

    @Test
    void reconnectUsesTheChangedSubscriptions() throws Exception {
        SimulatorServer s = simulator("--disconnect.after-seconds=0.5");
        RealtimeClient c = client(s, "A01612");
        c.setSubscriptions(List.of(new Subscription("H0IFCNT0", "A01703")));
        await(() -> c.connectCount() >= 2, "a second connection");
        Thread.sleep(200);
        received.clear();
        await(() -> received.size() >= 3, "trades after reconnect");
        assertTrue(received.stream().allMatch(t -> t.symbol().equals("A01703")));
    }
}
