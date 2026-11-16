package com.futuresriskops.collector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 가짜 HTTP 서버와 손으로 만든 합성 응답으로 요청 모양과 파싱을 본다. 가격·거래량·키·토큰은 모두 지어낸 값이다
 * (실제 KIS 응답을 옮기지 않았다). 응답 필드 이름과 순서만 명세 xlsx 를 따른다.
 */
class KisDailyBarClientTest {

    private static final Instant NOW = Instant.parse("2026-11-16T07:00:00Z");

    private final MockWebServer server = new MockWebServer();
    private KisDailyBarClient client;

    @BeforeEach
    void setUp() throws Exception {
        server.start();
        String base = server.url("/").toString();
        KisCredentials credentials = KisCredentials.fromEnv(
                Map.of("KIS_APP_KEY", "dummy-app-key", "KIS_APP_SECRET", "dummy-secret"), "KIS_APP_KEY", "KIS_APP_SECRET");
        var tokens = new KisAccessTokenClient(new OkHttpClient(), base, credentials, Clock.fixed(NOW, ZoneOffset.UTC));
        client = new KisDailyBarClient(new OkHttpClient(), base, credentials, tokens);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private void enqueueToken() {
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"dummy-token\",\"expires_in\":86400}"));
    }

    private static String row(String date, String open, String high, String low, String close, String vol,
                              String value, String mod) {
        return "{\"stck_bsop_date\":\"" + date + "\",\"futs_prpr\":\"" + close + "\",\"futs_oprc\":\"" + open
                + "\",\"futs_hgpr\":\"" + high + "\",\"futs_lwpr\":\"" + low + "\",\"acml_vol\":\"" + vol
                + "\",\"acml_tr_pbmn\":\"" + value + "\",\"mod_yn\":\"" + mod + "\"}";
    }

    private static String ok(String... rows) {
        return "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"synthetic\",\"output1\":{\"futs_shrn_iscd\":\"A01612\"},"
                + "\"output2\":[" + String.join(",", rows) + "]}";
    }

    @Test
    void sendsTheTrIdHeadersAndQueryFromTheSpec() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setBody(ok(row("20261113", "400.10", "401.20", "399.00", "400.50", "1000", "50000000", "N"))));

        client.fetch(KisDailyBarClient.MARKET_DAY, "A01612", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13));

        server.takeRequest(); // tokenP
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice", req.getRequestUrl().encodedPath());
        assertEquals("FHKIF03020100", req.getHeader("tr_id"));
        assertEquals("Bearer dummy-token", req.getHeader("authorization"));
        assertEquals("dummy-app-key", req.getHeader("appkey"));
        assertEquals("dummy-secret", req.getHeader("appsecret"));
        var url = req.getRequestUrl();
        assertEquals("F", url.queryParameter("FID_COND_MRKT_DIV_CODE"));
        assertEquals("A01612", url.queryParameter("FID_INPUT_ISCD"));
        assertEquals("20261109", url.queryParameter("FID_INPUT_DATE_1"));
        assertEquals("20261113", url.queryParameter("FID_INPUT_DATE_2"));
        assertEquals("D", url.queryParameter("FID_PERIOD_DIV_CODE"));
    }

    @Test
    void nightUsesTheCmMarketCode() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setBody(ok()));

        client.fetch(KisDailyBarClient.MARKET_NIGHT, "A01612", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13));

        server.takeRequest();
        assertEquals("CM", server.takeRequest().getRequestUrl().queryParameter("FID_COND_MRKT_DIV_CODE"));
    }

    @Test
    void parsesOutput2KeepingTheKisValuesAndSortsByDate() throws Exception {
        enqueueToken();
        // KIS 는 최근 일자부터 준다(가정). 일부러 내림차순으로 만든다
        server.enqueue(new MockResponse().setBody(ok(
                row("20261113", "400.10", "401.20", "399.00", "400.50", "1000", "50000000", "N"),
                row("20261112", "399.00", "400.00", "398.50", "399.90", "900", "45000000", "Y"))));

        List<DailyBar> bars = client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13));

        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 11, 12), bars.get(0).tradeDate());
        DailyBar last = bars.get(1);
        assertEquals(LocalDate.of(2026, 11, 13), last.tradeDate());
        assertEquals(new BigDecimal("400.10"), last.open());
        assertEquals(new BigDecimal("401.20"), last.high());
        assertEquals(new BigDecimal("399.00"), last.low());
        assertEquals(new BigDecimal("400.50"), last.close());
        assertEquals(1000L, last.volume());
        assertEquals(new BigDecimal("50000000"), last.tradeValue());
        assertEquals("N", last.modYn());
        assertEquals("Y", bars.get(0).modYn());
    }

    @Test
    void rowsWithoutAnyPriceAreDroppedButAZeroVolumeRowWithPricesIsKept() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setBody(ok(
                row("20261113", "0.00", "0.00", "0.00", "0.00", "0", "0", "N"),
                row("20261112", "399.00", "400.00", "398.50", "399.90", "0", "0", "N"))));

        List<DailyBar> bars = client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13));

        assertEquals(1, bars.size());
        assertEquals(LocalDate.of(2026, 11, 12), bars.get(0).tradeDate());
    }

    @Test
    void aFailureCodeFailsWithoutTheMessageText() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"expired dummy-token\"}"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)));
        assertTrue(e.getMessage().contains("rt_cd=1"));
        assertTrue(e.getMessage().contains("EGW00123"));
        assertFalse(e.getMessage().contains("dummy-token"));
    }

    @Test
    void aNonNumericValueFailsInsteadOfBeingSkipped() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setBody(ok(row("20261113", "400.10", "abc", "399.00", "400.50", "1000", "5", "N"))));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)));
        assertTrue(e.getMessage().contains("futs_hgpr"));
        assertFalse(e.getMessage().contains("abc"));
    }

    @Test
    void anAuthRejectionDropsTheTokenSoTheNextCallGetsANewOne() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("dummy-token rejected"));
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"dummy-token-2\",\"expires_in\":86400}"));
        server.enqueue(new MockResponse().setBody(ok(row("20261113", "400.10", "401.20", "399.00", "400.50", "1000", "5", "N"))));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)));
        assertTrue(e.getMessage().contains("401"));
        assertFalse(e.getMessage().contains("dummy-token"));

        assertEquals(1, client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)).size());
        server.takeRequest(); // tokenP
        server.takeRequest(); // 401
        server.takeRequest(); // tokenP again
        assertEquals("Bearer dummy-token-2", server.takeRequest().getHeader("authorization"));
    }

    @Test
    void aServerErrorKeepsTheToken() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setBody(ok(row("20261113", "400.10", "401.20", "399.00", "400.50", "1000", "5", "N"))));

        assertThrows(IllegalStateException.class,
                () -> client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13)));
        client.fetch("F", "A01612", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13));

        assertEquals(3, server.getRequestCount(), "one token request, two data requests");
    }
}
