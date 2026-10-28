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
}
