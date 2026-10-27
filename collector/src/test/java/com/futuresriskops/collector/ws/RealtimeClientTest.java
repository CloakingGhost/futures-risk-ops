package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.kis.H0ifcnt0Trade;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                Duration.ofMillis(500), Clock.systemUTC());
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
}
