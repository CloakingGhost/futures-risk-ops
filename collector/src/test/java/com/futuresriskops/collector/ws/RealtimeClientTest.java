package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
                List.of(symbols), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
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
                List.of("A01612"), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
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
                List.of("A01612"), ApprovalKeyProvider.simulator(), (t, at) -> received.add(t),
                new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), Duration.ZERO, new Random(1)),
                Clock.systemUTC());
        open.add(c);
        c.setStateListener((from, to, at) -> states.add(to));
        c.start();
        await(() -> states.stream().filter(x -> x == RealtimeClient.State.BACKOFF).count() >= 3, "three backoffs");
        assertEquals(0, c.connectCount());
        assertEquals(-1, c.lastRecoveryMillis(), "never subscribed, so nothing to recover");
    }
}
