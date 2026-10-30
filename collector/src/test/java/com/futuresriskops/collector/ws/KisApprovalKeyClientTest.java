package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 가짜 HTTP 서버로 요청 모양만 본다. 키 값은 테스트용 더미다. */
class KisApprovalKeyClientTest {

    private final MockWebServer server = new MockWebServer();
    private final Map<String, String> env = Map.of("KIS_APP_KEY", "dummy-app-key", "KIS_APP_SECRET", "dummy-secret");

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void postsAppKeyAndSecretAndCachesTheApprovalKey() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-approval\"}"));
        server.start();
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET");

        assertEquals("dummy-approval", client.approvalKey());
        assertEquals("dummy-approval", client.approvalKey());
        assertEquals(1, server.getRequestCount());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/oauth2/Approval", req.getPath());
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertEquals("client_credentials", body.path("grant_type").asText());
        assertEquals("dummy-app-key", body.path("appkey").asText());
        assertEquals("dummy-secret", body.path("secretkey").asText());
    }

    @Test
    void failsAtStartWithoutEnvironmentVariables() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new KisApprovalKeyClient(
                new OkHttpClient(), "http://127.0.0.1:1", Map.of(), "KIS_APP_KEY", "KIS_APP_SECRET"));
        assertTrue(e.getMessage().contains("KIS_APP_KEY"));
    }

    @Test
    void paperNamesReadOnlyThePaperVariablesAndNeverTheRealOnes() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-approval\"}"));
        server.start();
        Map<String, String> both = Map.of("KIS_APP_KEY", "real-key", "KIS_APP_SECRET", "real-secret",
                "KIS_PAPER_APP_KEY", "paper-key", "KIS_PAPER_APP_SECRET", "paper-secret");
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), both,
                "KIS_PAPER_APP_KEY", "KIS_PAPER_APP_SECRET");
        client.approvalKey();
        JsonNode body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("paper-key", body.path("appkey").asText());
        assertEquals("paper-secret", body.path("secretkey").asText());
    }

    @Test
    void aMissingPaperVariableFailsNamingThePaperVariableEvenWhenTheRealOnesExist() {
        Map<String, String> onlyReal = Map.of("KIS_APP_KEY", "real-key", "KIS_APP_SECRET", "real-secret");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new KisApprovalKeyClient(
                new OkHttpClient(), "http://127.0.0.1:1", onlyReal, "KIS_PAPER_APP_KEY", "KIS_PAPER_APP_SECRET"));
        assertTrue(e.getMessage().contains("KIS_PAPER_APP_KEY"));
        assertFalse(e.getMessage().contains("real-key"));
    }

    @Test
    void httpErrorsDoNotLeakTheSecret() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        server.start();
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET");
        IllegalStateException e = assertThrows(IllegalStateException.class, client::approvalKey);
        assertTrue(e.getMessage().contains("403"));
        assertFalse(e.getMessage().contains("dummy-secret"));
    }

    @Test
    void expiryIsTheIssueTimePlusTheAssumedTtlAndClearsOnInvalidate() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-approval\"}"));
        server.start();
        Instant issued = Instant.parse("2026-11-02T00:00:00Z");
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET", Clock.fixed(issued, ZoneOffset.UTC), Duration.ofHours(24));

        assertEquals(Optional.empty(), client.expiresAt(), "nothing issued yet");
        client.approvalKey();
        assertEquals(Optional.of(issued.plus(Duration.ofHours(24))), client.expiresAt());
        client.invalidate();
        assertEquals(Optional.empty(), client.expiresAt());
    }

    @Test
    void aFailedRequestLeavesNoExpiry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.start();
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET");
        assertThrows(IllegalStateException.class, client::approvalKey);
        assertEquals(Optional.empty(), client.expiresAt());
    }

    /** 테스트에서 시각을 옮기는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private KisApprovalKeyClient clientAt(MovableClock clock) {
        return new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET", clock, Duration.ofHours(24));
    }

    @Test
    void reusesTheKeyUntilAnHourBeforeExpiryThenGetsANewOne() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-1\"}"));
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-2\"}"));
        server.start();
        Instant issued = Instant.parse("2026-11-02T00:00:00Z");
        MovableClock clock = new MovableClock(issued);
        var client = clientAt(clock);

        assertEquals("dummy-1", client.approvalKey());
        clock.advance(Duration.ofHours(23).minusSeconds(1));
        assertEquals("dummy-1", client.approvalKey(), "still more than an hour left");
        assertEquals(1, server.getRequestCount());

        clock.advance(Duration.ofSeconds(1)); // 만료 1시간 전
        assertEquals("dummy-2", client.approvalKey());
        assertEquals(2, server.getRequestCount());
        assertEquals(Optional.of(issued.plus(Duration.ofHours(23 + 24))), client.expiresAt());
    }

    @Test
    void aFailedEarlyRefreshKeepsTheStillValidKeyButAnExpiredOneFails() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-1\"}"));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.start();
        Instant issued = Instant.parse("2026-11-02T00:00:00Z");
        MovableClock clock = new MovableClock(issued);
        var client = clientAt(clock);
        client.approvalKey();

        clock.advance(Duration.ofMinutes(23 * 60 + 30));
        assertEquals("dummy-1", client.approvalKey(), "30 minutes left, keep the current key");
        assertEquals(Optional.of(issued.plus(Duration.ofHours(24))), client.expiresAt());

        clock.advance(Duration.ofMinutes(30)); // 만료 시각
        assertThrows(IllegalStateException.class, client::approvalKey);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void invalidateMakesTheNextCallGetANewKey() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-1\"}"));
        server.enqueue(new MockResponse().setBody("{\"approval_key\":\"dummy-2\"}"));
        server.start();
        var client = clientAt(new MovableClock(Instant.parse("2026-11-02T00:00:00Z")));

        assertEquals("dummy-1", client.approvalKey());
        client.invalidate();
        assertEquals("dummy-2", client.approvalKey());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void theSimulatorKeyNeverExpires() {
        assertEquals(Optional.empty(), ApprovalKeyProvider.simulator().expiresAt());
    }
}
