package com.futuresriskops.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.simulator.config.SimulatorConfig;
import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.H0IFCNT0.Field;
import com.futuresriskops.simulator.ws.MiniJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 실제 소켓으로 서버를 띄우고 JDK 웹소켓 클라이언트로 붙는 통합 테스트. 시간 여유는 넉넉하게 잡았다. */
class SimulatorServerTest {

    private final List<SimulatorServer> servers = new ArrayList<>();
    private final List<TestClient> clients = new ArrayList<>();

    @AfterEach
    void cleanup() {
        clients.forEach(c -> {
            if (c.ws != null) {
                c.ws.abort();
            }
        });
        servers.forEach(SimulatorServer::close);
    }

    // ------------------------------------------------------------------ 도우미

    private SimulatorServer server(String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--price.seed=1", "--rate.ticks-per-second=100", "--rate.jitter=0",
                "--pingpong.interval-seconds=0"));
        args.addAll(List.of(extra));
        SimulatorServer s = new SimulatorServer(SimulatorConfig.load(args.toArray(String[]::new), Map.of()));
        s.start();
        servers.add(s);
        return s;
    }

    private TestClient connect(SimulatorServer s) throws Exception {
        TestClient c = new TestClient();
        c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + s.port() + "/tryitout"), c)
                .get(5, TimeUnit.SECONDS);
        clients.add(c);
        return c;
    }

    private static boolean isTrade(String text) {
        return text.startsWith("0|H0IFCNT0|001|");
    }

    private static String rtCd(String json) {
        return MiniJson.getString(MiniJson.parse(json), "body", "rt_cd");
    }

    private static final class Msg {
        final String text;
        final long nanos = System.nanoTime();

        Msg(String text) {
            this.text = text;
        }
    }

    private static final class TestClient implements WebSocket.Listener {
        final BlockingQueue<Msg> messages = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile WebSocket ws;
        volatile Integer closeCode;
        volatile Throwable error;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(new Msg(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable t) {
            error = t;
            closed.countDown();
        }

        void subscribe(String symbol) {
            send(request("1", "H0IFCNT0", symbol));
        }

        void unsubscribe(String symbol) {
            send(request("2", "H0IFCNT0", symbol));
        }

        void send(String text) {
            ws.sendText(text, true).join();
        }

        static String request(String trType, String trId, String trKey) {
            return "{\"header\":{\"approval_key\":\"dummy\",\"custtype\":\"P\",\"tr_type\":\"" + trType
                    + "\",\"content-type\":\"utf-8\"},\"body\":{\"input\":{\"tr_id\":\"" + trId
                    + "\",\"tr_key\":\"" + trKey + "\"}}}";
        }

        Msg next(long timeoutMs) throws InterruptedException {
            return messages.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** timeoutMs 안에 도착한 메시지를 모두 모은다. */
        List<Msg> collectFor(long timeoutMs) throws InterruptedException {
            List<Msg> out = new ArrayList<>();
            long end = System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return out;
                }
                Msg m = messages.poll(left, TimeUnit.MILLISECONDS);
                if (m != null) {
                    out.add(m);
                }
            }
        }

        void drain() {
            messages.clear();
        }
    }

    // ------------------------------------------------------------------ 테스트

    @Test
    void subscribeIsAcknowledgedThenTradesFlow() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);
        c.subscribe("101S12");

        Msg ack = c.next(3000);
        assertNotNull(ack);
        assertEquals("0", rtCd(ack.text));
        assertEquals("H0IFCNT0", MiniJson.getString(MiniJson.parse(ack.text), "header", "tr_id"));
        assertEquals("101S12", MiniJson.getString(MiniJson.parse(ack.text), "header", "tr_key"));
        assertEquals("SUBSCRIBE SUCCESS", MiniJson.getString(MiniJson.parse(ack.text), "body", "msg1"));

        for (int i = 0; i < 10; i++) {
            Msg m = c.next(3000);
            assertNotNull(m, "trade " + i);
            assertTrue(isTrade(m.text), m.text);
            H0IFCNT0.Record r = H0IFCNT0.parseFrame(m.text);
            assertEquals("101S12", r.get(Field.FUTS_SHRN_ISCD));
        }
    }

    @Test
    void tradesFollowTheConfiguredRate() throws Exception {
        SimulatorServer s = server("--rate.ticks-per-second=50");
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertNotNull(c.next(3000)); // ack
        c.drain();
        long trades = c.collectFor(2000).stream().filter(m -> isTrade(m.text)).count();
        assertTrue(trades >= 60 && trades <= 120, "expected about 100 trades in 2s but got " + trades);
    }

    @Test
    void symbolsAreIndependentFeeds() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);
        c.subscribe("AAA111");
        c.subscribe("BBB222");
        Set<String> symbols = new HashSet<>();
        for (Msg m : c.collectFor(1000)) {
            if (isTrade(m.text)) {
                symbols.add(H0IFCNT0.parseFrame(m.text).get(Field.FUTS_SHRN_ISCD));
            }
        }
        assertEquals(Set.of("AAA111", "BBB222"), symbols);
    }

    @Test
    void unsubscribeStopsTrades() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertNotNull(c.next(3000));
        assertNotNull(c.next(3000)); // 체결이 흐르는 것 확인
        c.unsubscribe("101S12");
        // UNSUBSCRIBE 응답이 올 때까지 체결을 버린다
        boolean sawAck = false;
        for (int i = 0; i < 500 && !sawAck; i++) {
            Msg m = c.next(1000);
            assertNotNull(m);
            sawAck = !isTrade(m.text) && m.text.contains("UNSUBSCRIBE SUCCESS");
        }
        assertTrue(sawAck);
        Thread.sleep(100); // 이미 보낸 체결이 남아 있을 수 있어 잠깐 기다렸다가 비운다
        c.drain();
        assertEquals(0, c.collectFor(500).stream().filter(m -> isTrade(m.text)).count());
    }

    private static long accVolume(Msg m) {
        return Long.parseLong(H0IFCNT0.parseFrame(m.text).get(Field.ACML_VOL));
    }

    private static long qty(Msg m) {
        return Long.parseLong(H0IFCNT0.parseFrame(m.text).get(Field.LAST_CNQN));
    }

    @Test
    void feedKeepsTradingWhileNobodyIsSubscribedSoTheGapShowsInAccVolume() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);
        c.subscribe("101S12");
        nextTrade(c);
        c.unsubscribe("101S12");
        long lastAcc = -1;
        for (int i = 0; i < 500; i++) {
            Msg m = c.next(1000);
            assertNotNull(m);
            if (isTrade(m.text)) {
                lastAcc = accVolume(m);
            } else if (m.text.contains("UNSUBSCRIBE SUCCESS")) {
                break;
            }
        }
        Thread.sleep(300); // 초당 100건이므로 이 사이 약 30건이 구독자 없이 지나간다
        c.drain();
        c.subscribe("101S12");
        Msg first = nextTrade(c);
        assertTrue(accVolume(first) - lastAcc > qty(first),
                "acc_volume should jump over the trades made while unsubscribed");
    }

    @Test
    void resendOnSubscribeRepeatsRecentTradesTheOtherClientAlreadyHad() throws Exception {
        SimulatorServer s = server("--resend.on-subscribe=3");
        TestClient a = connect(s);
        a.subscribe("101S12");
        Set<Long> seenByA = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seenByA.add(accVolume(nextTrade(a)));
        }
        TestClient b = connect(s);
        b.subscribe("101S12");
        long resent = accVolume(nextTrade(b));
        for (Msg m : a.collectFor(300)) {
            if (isTrade(m.text)) {
                seenByA.add(accVolume(m));
            }
        }
        assertTrue(seenByA.contains(resent), "the first trade for a new subscriber is an old one");
    }

    @Test
    void badRequestsGetErrorsAndKeepTheConnectionOpen() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);

        c.send("not json");
        assertEquals("1", rtCd(c.next(3000).text));
        c.send(TestClient.request("1", "H0STCNT0", "005930"));
        assertEquals("1", rtCd(c.next(3000).text));
        c.send(TestClient.request("9", "H0IFCNT0", "101S12"));
        assertEquals("1", rtCd(c.next(3000).text));
        c.send("{\"header\":{\"tr_type\":\"1\"},\"body\":{\"input\":{\"tr_id\":\"H0IFCNT0\"}}}");
        assertEquals("1", rtCd(c.next(3000).text));

        c.subscribe("101S12"); // 같은 연결이 여전히 쓸 수 있다
        assertEquals("0", rtCd(c.next(3000).text));
    }

    @Test
    void subscriptionLimitIsEnforcedPerConnection() throws Exception {
        SimulatorServer s = server("--server.max-subscriptions=2");
        TestClient c = connect(s);
        c.subscribe("AAA111");
        assertEquals("0", rtCd(firstNonTrade(c)));
        c.subscribe("BBB222");
        assertEquals("0", rtCd(firstNonTrade(c)));
        c.subscribe("CCC333");
        assertEquals("1", rtCd(firstNonTrade(c)));
    }

    private static String firstNonTrade(TestClient c) throws InterruptedException {
        for (int i = 0; i < 2000; i++) {
            Msg m = c.next(3000);
            assertNotNull(m);
            if (!isTrade(m.text)) {
                return m.text;
            }
        }
        throw new AssertionError("no control message arrived");
    }

    @Test
    void plainHttpRequestGets400() throws Exception {
        SimulatorServer s = server();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, r.statusCode());
    }

    @Test
    void pingPongMessagesArriveAtTheConfiguredInterval() throws Exception {
        SimulatorServer s = server("--pingpong.interval-seconds=0.4");
        TestClient c = connect(s);
        Msg ping = c.next(3000);
        assertNotNull(ping);
        assertEquals("PINGPONG", MiniJson.getString(MiniJson.parse(ping.text), "header", "tr_id"));
        assertTrue(MiniJson.getString(MiniJson.parse(ping.text), "header", "datetime").matches("\\d{14}"));
        Msg second = c.next(3000);
        assertNotNull(second);
        long gapMs = (second.nanos - ping.nanos) / 1_000_000L;
        assertTrue(gapMs >= 250 && gapMs <= 1500, "gap " + gapMs);
        c.ws.sendPong(java.nio.ByteBuffer.wrap(ping.text.getBytes())).join(); // KIS 샘플처럼 되돌려 보내도 문제없다
        assertFalse(c.closed.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void echoedPingPongTextIsCountedAndGetsNoReply() throws Exception {
        SimulatorServer s = server("--pingpong.interval-seconds=0.3");
        TestClient c = connect(s);
        Msg ping = c.next(3000);
        assertNotNull(ping);
        c.send(ping.text); // KIS 클라이언트처럼 받은 그대로 돌려보낸다
        long end = System.nanoTime() + 3_000_000_000L;
        while (s.pingPongEchoCount() < 1 && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertEquals(1, s.pingPongEchoCount());
        for (Msg m : c.collectFor(400)) {
            assertEquals("PINGPONG", MiniJson.getString(MiniJson.parse(m.text), "header", "tr_id"),
                    "an echoed PINGPONG must not get an error reply: " + m.text);
        }
    }

    @Test
    void customTradeSourceReplacesTheRandomWalk() throws Exception {
        String[] values = new String[H0IFCNT0.FIELD_COUNT];
        java.util.Arrays.fill(values, "0");
        values[Field.FUTS_SHRN_ISCD.ordinal()] = "FIXED1";
        values[Field.FUTS_PRPR.ordinal()] = "123.45";
        H0IFCNT0.Record fixed = new H0IFCNT0.Record(values);
        SimulatorConfig cfg = SimulatorConfig.load(new String[] {
                "--server.port=0", "--rate.ticks-per-second=50", "--pingpong.interval-seconds=0"}, Map.of());
        SimulatorServer s = new SimulatorServer(cfg, java.time.Clock.system(SimulatorServer.KST),
                symbol -> new com.futuresriskops.simulator.market.TradeSource() {
                    @Override
                    public String symbol() {
                        return symbol;
                    }

                    @Override
                    public H0IFCNT0.Record next(java.time.LocalTime now) {
                        return fixed;
                    }
                });
        s.start();
        servers.add(s);
        TestClient c = connect(s);
        c.subscribe("FIXED1");
        Msg trade = null;
        for (int i = 0; i < 10 && trade == null; i++) {
            Msg m = c.next(3000);
            if (m != null && isTrade(m.text)) {
                trade = m;
            }
        }
        assertNotNull(trade);
        assertEquals(fixed.toFrame(), trade.text);
    }

    @Test
    void serverClosesTheConnectionAfterTheConfiguredTimeAndReconnectWorks() throws Exception {
        SimulatorServer s = server("--disconnect.after-seconds=1");
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertTrue(c.closed.await(5, TimeUnit.SECONDS), "connection should be closed by the server");
        assertEquals(Integer.valueOf(1001), c.closeCode);

        TestClient again = connect(s); // 타이머는 연결마다 새로 시작한다
        again.subscribe("101S12");
        assertEquals("0", rtCd(firstNonTrade(again)));
        assertFalse(again.closed.await(500, TimeUnit.MILLISECONDS), "second connection has its own timer");
    }

    @Test
    void abruptDisconnectSkipsTheCloseHandshake() throws Exception {
        SimulatorServer s = server("--disconnect.after-seconds=1", "--disconnect.abrupt=true");
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertTrue(c.closed.await(5, TimeUnit.SECONDS));
        boolean graceful = c.closeCode != null && c.closeCode == 1001;
        assertFalse(graceful, "abrupt mode must not send close code 1001");
    }

    @Test
    void randomDisconnectEventuallyHappens() throws Exception {
        SimulatorServer s = server("--disconnect.probability-per-tick=0.05");
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertTrue(c.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void fixedDelayPostponesTradesButNotTheAck() throws Exception {
        SimulatorServer fast = server();
        TestClient a = connect(fast);
        a.subscribe("101S12");
        Msg ackA = a.next(3000);
        Msg tradeA = nextTrade(a);
        long fastMs = (tradeA.nanos - ackA.nanos) / 1_000_000L;

        SimulatorServer slow = server("--delay.fixed-ms=500");
        TestClient b = connect(slow);
        b.subscribe("101S12");
        Msg ackB = b.next(3000);
        Msg tradeB = nextTrade(b);
        long slowMs = (tradeB.nanos - ackB.nanos) / 1_000_000L;

        assertTrue(fastMs < 400, "without delay the first trade should come quickly but took " + fastMs);
        assertTrue(slowMs >= 450, "with 500ms delay the first trade took only " + slowMs);
    }

    private static Msg nextTrade(TestClient c) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Msg m = c.next(3000);
            assertNotNull(m);
            if (isTrade(m.text)) {
                return m;
            }
        }
        throw new AssertionError("no trade arrived");
    }

    @Test
    void delayJitterKeepsTradeOrder() throws Exception {
        SimulatorServer s = server("--delay.fixed-ms=50", "--delay.jitter-ms=300",
                "--delay.spike-probability=0.1", "--delay.spike-ms=400");
        TestClient c = connect(s);
        c.subscribe("101S12");
        long last = -1;
        int seen = 0;
        for (Msg m : c.collectFor(2500)) {
            if (!isTrade(m.text)) {
                continue;
            }
            long accVol = Long.parseLong(H0IFCNT0.parseFrame(m.text).get(Field.ACML_VOL));
            assertTrue(accVol > last, "trades must arrive in order: " + last + " then " + accVol);
            last = accVol;
            seen++;
        }
        assertTrue(seen > 50, "only " + seen + " trades arrived");
    }

    @Test
    void stallStopsTradesWithoutClosingTheConnection() throws Exception {
        SimulatorServer s = server("--stall.after-seconds=0.5", "--stall.duration-seconds=1.5");
        TestClient c = connect(s);
        c.subscribe("101S12");
        List<Msg> msgs = c.collectFor(3500);
        List<Msg> trades = new ArrayList<>();
        for (Msg m : msgs) {
            if (isTrade(m.text)) {
                trades.add(m);
            }
        }
        long maxGapMs = 0;
        for (int i = 1; i < trades.size(); i++) {
            maxGapMs = Math.max(maxGapMs, (trades.get(i).nanos - trades.get(i - 1).nanos) / 1_000_000L);
        }
        assertTrue(maxGapMs >= 1200, "expected a gap of about 1.5s but the largest was " + maxGapMs + "ms");
        assertFalse(c.closed.await(10, TimeUnit.MILLISECONDS), "connection must stay open during a stall");
        Msg last = trades.get(trades.size() - 1);
        assertTrue((last.nanos - msgs.get(0).nanos) / 1_000_000L > 2200, "trades should resume after the stall");
    }

    @Test
    void connectedClientsShareTheSameMarket() throws Exception {
        SimulatorServer s = server();
        TestClient a = connect(s);
        TestClient b = connect(s);
        a.subscribe("101S12");
        b.subscribe("101S12");
        Set<String> framesA = new HashSet<>();
        Set<String> framesB = new HashSet<>();
        Thread.sleep(1000);
        for (Msg m : a.messages) {
            if (isTrade(m.text)) {
                framesA.add(m.text);
            }
        }
        for (Msg m : b.messages) {
            if (isTrade(m.text)) {
                framesB.add(m.text);
            }
        }
        framesA.retainAll(framesB);
        assertTrue(framesA.size() >= 10, "both clients should see the same trades, shared " + framesA.size());
    }

    @Test
    void closingTheServerClosesClients() throws Exception {
        SimulatorServer s = server();
        TestClient c = connect(s);
        c.subscribe("101S12");
        assertNotNull(c.next(3000));
        s.close();
        assertTrue(c.closed.await(5, TimeUnit.SECONDS));
    }
}
