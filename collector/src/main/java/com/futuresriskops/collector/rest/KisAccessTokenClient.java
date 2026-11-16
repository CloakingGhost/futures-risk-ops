package com.futuresriskops.collector.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIS {@code POST /oauth2/tokenP} 로 REST 접근토큰(access token)을 받는다. 일봉 같은 REST 시세 조회에 쓴다.
 * 웹소켓 접속키({@code KisApprovalKeyClient})와는 다른 키다.
 *
 * <p>요청 본문은 공식 샘플(kis_auth.py)과 같은 {@code grant_type, appkey, appsecret} 다(가정: 샘플 기준, 이 명세 xlsx 에는 없음).
 * 응답의 {@code access_token}, {@code expires_in}(초, 가정: 샘플 기준)을 쓴다. {@code expires_in} 이 없으면
 * {@link #DEFAULT_TTL}(명세 xlsx: 일반고객 유효기간 1일)로 계산한다.
 *
 * <p>앱키·시크릿은 환경변수에서만 읽고(이름은 프로필마다 설정), 토큰은 메모리에만 둔다. 로그·예외 메시지에는
 * 키·시크릿·토큰 값을 쓰지 않는다. 만료 {@link #REFRESH_BEFORE} 전부터는 호출할 때 새 토큰을 받는다.
 * 미리 받으려다 실패하면 아직 만료 전인 토큰을 그대로 쓰고, 다음 호출 때 다시 받는다.
 */
public final class KisAccessTokenClient {

    private static final Logger LOG = LoggerFactory.getLogger(KisAccessTokenClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /** 명세 xlsx: 일반고객 접근토큰 유효기간 1일. 응답에 expires_in 이 없을 때만 쓴다. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(1);
    /** 만료 이만큼 전부터는 호출할 때 새 토큰을 받는다. */
    public static final Duration REFRESH_BEFORE = Duration.ofHours(1);

    private final OkHttpClient http;
    private final String restBaseUrl;
    private final String appKey;
    private final String appSecret;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String cached;
    private volatile Instant expiresAt;

    /**
     * @param env          보통 {@code System.getenv()}
     * @param appKeyEnv    앱키를 담은 환경변수 이름. 없거나 비어 있으면 여기서 바로 실패한다
     * @param appSecretEnv 시크릿을 담은 환경변수 이름
     */
    public KisAccessTokenClient(OkHttpClient http, String restBaseUrl, Map<String, String> env, String appKeyEnv,
                                String appSecretEnv, Clock clock) {
        this.http = http;
        this.restBaseUrl = restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
        this.clock = clock;
        this.appKey = required(env, appKeyEnv);
        this.appSecret = required(env, appSecretEnv);
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is required for the KIS REST access token (set in the terminal that runs the collector)");
        }
        return v.trim();
    }

    public String accessToken() {
        String token = cached;
        if (token != null && !dueForRefresh(clock.instant())) {
            return token;
        }
        synchronized (this) {
            Instant now = clock.instant();
            if (cached != null && !dueForRefresh(now)) {
                return cached;
            }
            try {
                request(now);
                return cached;
            } catch (RuntimeException e) {
                Instant exp = expiresAt;
                if (cached != null && exp != null && now.isBefore(exp)) {
                    LOG.warn("access token refresh failed, keeping the current token until it expires: {}", e.getMessage());
                    return cached;
                }
                throw e;
            }
        }
    }

    private boolean dueForRefresh(Instant now) {
        Instant exp = expiresAt;
        return exp == null || !now.isBefore(exp.minus(REFRESH_BEFORE));
    }

    /** 서버가 토큰을 거절했다고 보이면 다음 호출 때 새로 받는다. */
    public synchronized void invalidate() {
        cached = null;
        expiresAt = null;
    }

    private void request(Instant now) {
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "grant_type", "client_credentials", "appkey", appKey, "appsecret", appSecret));
        } catch (IOException e) {
            throw new IllegalStateException("could not build access token request", e);
        }
        Request req = new Request.Builder()
                .url(restBaseUrl + "/oauth2/tokenP")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw new IllegalStateException("access token request failed with HTTP " + res.code());
            }
            JsonNode node = json.readTree(res.body().string());
            String token = node.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("access token response has no access_token");
            }
            long seconds = node.path("expires_in").asLong(0);
            Duration ttl = seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_TTL;
            cached = token;
            expiresAt = now.plus(ttl);
            LOG.info("access token issued, valid for {}", ttl);
        } catch (IOException e) {
            throw new IllegalStateException("access token request failed: " + e.getClass().getSimpleName());
        }
    }
}
