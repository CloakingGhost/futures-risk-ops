package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.PostgresTestSupport;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Day 2 끊김 실험을 자동으로: 시뮬레이터가 0.7초마다 연결을 끊고, 수집기는 0.3초 기다렸다 다시 붙는다(초당 50건이라 그사이 약 15건이 지나간다).
 * 끊김 세 번 뒤 확인한다.
 *
 * <ul>
 *   <li>끊김마다 30초 안에 SUBSCRIBED 로 돌아온다.</li>
 *   <li>재전송이 없으면 끊긴 동안의 체결을 놓친다. 누락 카운터의 누락 수량 = DB 누적거래량 사이 빈틈의 합.</li>
 *   <li>다시 구독할 때 시뮬레이터가 최근 30건을 다시 보내면 이미 받은 체결이 섞인다. 그 체결(REPEAT)은 DB 에 두 번 쌓이지 않는다.</li>
 * </ul>
 * Docker 가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReconnectExperimentTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.migrated(PG);
        jdbc.update("TRUNCATE tick_trade");
    }

    private IngestService runThreeDrops(String... simulatorExtra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--price.seed=21",
                "--rate.ticks-per-second=50", "--pingpong.interval-seconds=0", "--disconnect.after-seconds=0.7"));
        args.addAll(List.of(simulatorExtra));
        try (SimulatorServer sim = new SimulatorServer(SimulatorConfig.load(args.toArray(String[]::new), Map.of()))) {
            sim.start();
            CollectorProperties p = IngestServiceTest.props(sim.port(), false, Duration.ofSeconds(30));
            p = new CollectorProperties(p.enabled(), p.source(), p.wsUrl(), p.symbols(),
                    new CollectorProperties.Reconnect(Duration.ofMillis(300), Duration.ofMillis(300), Duration.ZERO),
                    p.session(), p.stallThreshold(), false, p.approval(), p.batch());
            IngestService svc = new IngestService(p, TestCalendars.standard(), new TickTradeRepository(jdbc)::insertBatch,
                    new OkHttpClient(), Map.of(), Clock.systemUTC());
            svc.start();
            long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (svc.client().recoveryCount() < 3 && System.nanoTime() < end) {
                Thread.sleep(50);
            }
            Thread.sleep(200); // 마지막 재구독 직후 체결까지 받는다
            svc.stop();
            System.out.println("reconnect experiment " + args + ": " + svc.summary());

            assertTrue(svc.client().recoveryCount() >= 3, "three recoveries");
            assertTrue(svc.client().maxRecoveryMillis() < 30_000, "each recovery within 30 s");
            assertEquals(0, svc.writer().droppedCount());
            long rows = jdbc.queryForObject("SELECT count(*) FROM tick_trade", Long.class);
            assertEquals(svc.writer().writtenCount(), rows);
            assertEquals(rows, jdbc.queryForObject("SELECT count(DISTINCT acc_volume) FROM tick_trade", Long.class));
            return svc;
        }
    }

    /** DB 에 쌓인 누적거래량 사이 빈틈의 합(첫 행 이후). */
    private long holesInDatabase() {
        List<long[]> seq = jdbc.query("SELECT acc_volume, qty FROM tick_trade ORDER BY acc_volume",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
        long holes = 0;
        for (int i = 1; i < seq.size(); i++) {
            holes += seq.get(i)[0] - seq.get(i)[1] - seq.get(i - 1)[0];
        }
        return holes;
    }

    @Test
    void tradesMissedWhileDisconnectedAreCountedExactly() throws Exception {
        IngestService svc = runThreeDrops();
        assertTrue(svc.gaps().reconnectGapCount() >= 3, "a gap after every reconnect");
        assertEquals(0, svc.gaps().streamGapCount(), "no gap while connected");
        assertEquals(svc.gaps().missingVolume(), svc.gaps().reconnectMissingVolume());
        assertEquals(svc.gaps().missingVolume(), holesInDatabase(), "missing volume equals the holes in acc_volume");
        assertEquals(0, svc.writer().duplicateCount());
    }

    @Test
    void tradesSentAgainAfterReconnectAreStoredOnce() throws Exception {
        IngestService svc = runThreeDrops("--resend.on-subscribe=30");
        assertTrue(svc.gaps().repeatCount() > 0, "the simulator resent trades we already had");
        assertEquals(svc.gaps().repeatCount(), svc.writer().duplicateCount(),
                "every resent trade was dropped by the UNIQUE key, nothing else");
        assertEquals(0, svc.gaps().missingVolume(), "the resend covered the disconnected time");
        assertEquals(0, holesInDatabase());
    }
}
