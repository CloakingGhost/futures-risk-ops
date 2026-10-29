package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.collector.store.TickBatchWriter;
import com.futuresriskops.collector.ws.ApprovalKeyProvider;
import com.futuresriskops.collector.ws.RealtimeClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class IngestSummaryTest {

    @Test
    void summaryShowsEveryCounterEvenBeforeAnythingHappened() {
        RealtimeClient client = new RealtimeClient(new OkHttpClient(), "ws://127.0.0.1:1/x", List.of("A01612"),
                ApprovalKeyProvider.simulator(), (t, at) -> { }, Duration.ofSeconds(1), Clock.systemUTC());
        TickBatchWriter writer = new TickBatchWriter(b -> b.size(), 10, 10, 50);
        assertEquals("connects=0, trades=0, written=0, duplicates=0, dropped=0, parse-errors=0, subscribe-errors=0",
                IngestService.summary(client, writer));
    }
}
