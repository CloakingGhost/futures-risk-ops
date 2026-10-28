package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.store.PostgresTestSupport;
import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.RealtimeClient;
import com.futuresriskops.simulator.SimulatorServer;
import com.futuresriskops.simulator.config.SimulatorConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 시뮬레이터 → 웹소켓 → 파서 → 배치 적재 → PostgreSQL 전체를 초당 100건으로 돌려 유실이 없는지 본다.
 * 유실 판단: 받은 체결 수와 DB 행 수가 같고, 누적거래량이 (이전 값 + 이번 수량) 으로 빈틈없이 이어진다.
 * Docker 가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestThroughputTest {

    private static final int RATE = 100;
    private static final Duration RUN = Duration.ofSeconds(5);

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    @Test
    void hundredTradesPerSecondAreStoredWithoutLoss() throws Exception {
        JdbcTemplate jdbc = PostgresTestSupport.migrated(PG);
        TickTradeRepository repo = new TickTradeRepository(jdbc);

        SimulatorConfig cfg = SimulatorConfig.load(new String[] {"--server.port=0", "--price.seed=11",
                "--rate.ticks-per-second=" + RATE, "--pingpong.interval-seconds=1"}, Map.of());
        try (SimulatorServer sim = new SimulatorServer(cfg)) {
            sim.start();
            TickBatchWriter writer = new TickBatchWriter(repo::insertBatch, 100_000, 500, 200);
            writer.start();
            RealtimeClient client = new RealtimeClient(new OkHttpClient(),
                    "ws://127.0.0.1:" + sim.port() + "/tryitout", List.of("A01612"), ApprovalKeyProvider.simulator(),
                    (t, at) -> writer.offer(TickTrade.of(t, at, TickTrade.Source.SIMULATOR)),
                    Duration.ofSeconds(1), Clock.systemUTC());
            client.start();
            Thread.sleep(RUN.toMillis());
            client.close();
            writer.close();

            long received = client.tradeCount();
            System.out.printf("throughput: received=%d in %ds (%.1f/s), written=%d, duplicates=%d, dropped=%d%n",
                    received, RUN.toSeconds(), received / (double) RUN.toSeconds(), writer.writtenCount(),
                    writer.duplicateCount(), writer.droppedCount());

            assertTrue(received >= RATE * RUN.toSeconds() * 0.8, "too few trades received: " + received);
            assertEquals(1, client.connectCount());
            assertEquals(0, client.parseErrorCount());
            assertEquals(0, writer.droppedCount());
            assertEquals(0, writer.duplicateCount());
            assertEquals(received, writer.writtenCount());
            assertEquals(received, jdbc.queryForObject("SELECT count(*) FROM tick_trade", Long.class));

            // 시뮬레이터 누적거래량은 체결마다 수량만큼 는다. 한 건이라도 빠지면 이어지지 않는다.
            List<long[]> rows = jdbc.query("SELECT acc_volume, qty FROM tick_trade ORDER BY acc_volume",
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
            long prev = 0;
            for (long[] r : rows) {
                assertEquals(prev + r[1], r[0], "gap in acc_volume before " + r[0]);
                prev = r[0];
            }
        }
    }
}
