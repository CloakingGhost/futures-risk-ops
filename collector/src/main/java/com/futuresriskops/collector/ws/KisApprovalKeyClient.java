package com.futuresriskops.collector.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * KIS {@code POST /oauth2/Approval} 로 웹소켓 접속키를 받는다.
 *
 * <p>요청 본문은 공식 샘플(kis_auth.py)과 같은 {@code grant_type, appkey, secretkey} 다(가정: 샘플 기준, 문서로 재확인 필요).
 * 앱키·시크릿은 환경변수에서만 읽고(이름은 프로필마다 설정: 실전 {@code KIS_APP_KEY}/{@code KIS_APP_SECRET},
 * 모의투자 {@code KIS_PAPER_APP_KEY}/{@code KIS_PAPER_APP_SECRET}), 로그·예외 메시지에 값을 넣지 않는다. 메시지에는 환경변수 이름만 쓴다.
 * 받은 접속키는 메모리에만 두고 프로세스가 살아 있는 동안 다시 쓴다.
 */
public final class KisApprovalKeyClient implements ApprovalKeyProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String restBaseUrl;
    private final String appKey;
    private final String appSecret;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String cached;

    /**
     * @param env          보통 {@code System.getenv()}
     * @param appKeyEnv    앱키를 담은 환경변수 이름. 없거나 비어 있으면 여기서 바로 실패한다
     * @param appSecretEnv 시크릿을 담은 환경변수 이름
     */
    public KisApprovalKeyClient(OkHttpClient http, String restBaseUrl, Map<String, String> env, String appKeyEnv,
                                String appSecretEnv) {
        this.http = http;
        this.restBaseUrl = restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
        this.appKey = required(env, appKeyEnv);
        this.appSecret = required(env, appSecretEnv);
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is required for collector.approval.mode=KIS (set in the terminal that runs the collector)");
        }
        return v.trim();
    }

    @Override
    public String approvalKey() {
        String key = cached;
        if (key == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = request();
                }
                key = cached;
            }
        }
        return key;
    }

    /** 접속키가 거절됐다고 보이면 다음 접속 때 새로 받는다. */
    public void invalidate() {
        cached = null;
    }

    private String request() {
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "grant_type", "client_credentials", "appkey", appKey, "secretkey", appSecret));
        } catch (IOException e) {
            throw new IllegalStateException("could not build approval request", e);
        }
        Request req = new Request.Builder()
                .url(restBaseUrl + "/oauth2/Approval")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IllegalStateException("approval request failed with HTTP " + res.code());
            }
            JsonNode node = json.readTree(res.body().string());
            String key = node.path("approval_key").asText("");
            if (key.isBlank()) {
                throw new IllegalStateException("approval response has no approval_key");
            }
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("approval request failed: " + e.getClass().getSimpleName());
        }
    }
}
