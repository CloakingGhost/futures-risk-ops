package com.futuresriskops.collector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 가짜 HTTP 서버로 요청 모양과 재발급 시점만 본다. 키·토큰 값은 테스트용 더미(합성)다. */
class KisAccessTokenClientTest {

    private static final Instant T0 = Instant.parse("2026-11-16T00:00:00Z");

    private final MockWebServer server = new MockWebServer();
    private final Map<String, String> env = Map.of("KIS_APP_KEY", "dummy-app-key", "KIS_APP_SECRET", "dummy-secret");

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static String tokenBody(String token, long expiresInSeconds) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresInSeconds + "}";
    }

    private KisAccessTokenClient clientAt(MovableClock clock) {
        return new KisAccessTokenClient(new OkHttpClient(), server.url("/").toString(), env, "KIS_APP_KEY",
                "KIS_APP_SECRET", clock);
    }

    @Test
    void postsAppKeyAndSecretToTokenPAndCachesTheToken() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-token", 86400)));
        server.start();
        var client = clientAt(new MovableClock(T0));

        assertEquals("dummy-token", client.accessToken());
        assertEquals("dummy-token", client.accessToken());
        assertEquals(1, server.getRequestCount());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/oauth2/tokenP", req.getPath());
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertEquals("client_credentials", body.path("grant_type").asText());
        assertEquals("dummy-app-key", body.path("appkey").asText());
        assertEquals("dummy-secret", body.path("appsecret").asText());
    }

    @Test
    void failsAtStartWithoutEnvironmentVariables() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new KisAccessTokenClient(
                new OkHttpClient(), "http://127.0.0.1:1", Map.of(), "KIS_APP_KEY", "KIS_APP_SECRET", Clock.systemUTC()));
        assertTrue(e.getMessage().contains("KIS_APP_KEY"));
    }

    @Test
    void paperNamesReadOnlyThePaperVariables() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-token", 86400)));
        server.start();
        Map<String, String> both = Map.of("KIS_APP_KEY", "real-key", "KIS_APP_SECRET", "real-secret",
                "KIS_PAPER_APP_KEY", "paper-key", "KIS_PAPER_APP_SECRET", "paper-secret");
        var client = new KisAccessTokenClient(new OkHttpClient(), server.url("/").toString(), both,
                "KIS_PAPER_APP_KEY", "KIS_PAPER_APP_SECRET", Clock.fixed(T0, ZoneOffset.UTC));
        client.accessToken();
        JsonNode body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("paper-key", body.path("appkey").asText());
        assertEquals("paper-secret", body.path("appsecret").asText());
    }

    @Test
    void errorsNameNeitherTheSecretNorAToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden dummy-secret"));
        server.enqueue(new MockResponse().setBody("{\"msg\":\"no token here\"}"));
        server.start();
        var client = clientAt(new MovableClock(T0));
        IllegalStateException http = assertThrows(IllegalStateException.class, client::accessToken);
        assertTrue(http.getMessage().contains("403"));
        assertFalse(http.getMessage().contains("dummy-secret"));
        IllegalStateException empty = assertThrows(IllegalStateException.class, client::accessToken);
        assertTrue(empty.getMessage().contains("access_token"));
        assertFalse(empty.getMessage().contains("dummy-secret"));
    }

    @Test
    void reusesTheTokenUntilAnHourBeforeExpiryThenGetsANewOne() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-1", 86400)));
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-2", 86400)));
        server.start();
        MovableClock clock = new MovableClock(T0);
        var client = clientAt(clock);

        assertEquals("dummy-1", client.accessToken());
        clock.advance(Duration.ofHours(23).minusSeconds(1));
        assertEquals("dummy-1", client.accessToken(), "still more than an hour left");
        assertEquals(1, server.getRequestCount());

        clock.advance(Duration.ofSeconds(1)); // 만료 1시간 전
        assertEquals("dummy-2", client.accessToken());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void withoutExpiresInItAssumesOneDay() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"dummy-1\"}"));
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"dummy-2\"}"));
        server.start();
        MovableClock clock = new MovableClock(T0);
        var client = clientAt(clock);

        assertEquals("dummy-1", client.accessToken());
        clock.advance(Duration.ofHours(23).minusSeconds(1));
        assertEquals("dummy-1", client.accessToken());
        clock.advance(Duration.ofSeconds(1));
        assertEquals("dummy-2", client.accessToken());
    }

    @Test
    void aFailedEarlyRefreshKeepsTheStillValidTokenButAnExpiredOneFails() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-1", 86400)));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.start();
        MovableClock clock = new MovableClock(T0);
        var client = clientAt(clock);
        client.accessToken();

        clock.advance(Duration.ofMinutes(23 * 60 + 30));
        assertEquals("dummy-1", client.accessToken(), "30 minutes left, keep the current token");

        clock.advance(Duration.ofMinutes(30)); // 만료 시각
        assertThrows(IllegalStateException.class, client::accessToken);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void invalidateMakesTheNextCallGetANewToken() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-1", 86400)));
        server.enqueue(new MockResponse().setBody(tokenBody("dummy-2", 86400)));
        server.start();
        var client = clientAt(new MovableClock(T0));

        assertEquals("dummy-1", client.accessToken());
        client.invalidate();
        assertEquals("dummy-2", client.accessToken());
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
