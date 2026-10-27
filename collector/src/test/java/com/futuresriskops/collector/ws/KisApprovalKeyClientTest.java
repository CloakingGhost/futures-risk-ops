package com.futuresriskops.collector.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env);

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
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new KisApprovalKeyClient(new OkHttpClient(), "http://127.0.0.1:1", Map.of()));
        assertTrue(e.getMessage().contains("KIS_APP_KEY"));
    }

    @Test
    void httpErrorsDoNotLeakTheSecret() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        server.start();
        var client = new KisApprovalKeyClient(new OkHttpClient(), server.url("/").toString(), env);
        IllegalStateException e = assertThrows(IllegalStateException.class, client::approvalKey);
        assertTrue(e.getMessage().contains("403"));
        assertFalse(e.getMessage().contains("dummy-secret"));
    }
}
