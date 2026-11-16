package com.futuresriskops.collector.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIS 선물옵션기간별시세(일/주/월/년) {@code FHKIF03020100} 로 일봉을 받는다(시세 조회 GET 하나뿐이다).
 * 명세는 {@code KIS-API/[국내선물옵션]_기본시세.xlsx} 의 '선물옵션기간별시세(일_주_월_년)' 시트다.
 *
 * <p>요청: {@code GET /uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice}, 헤더에
 * 접근토큰·앱키·시크릿·tr_id, 쿼리에 시장 분류 코드(F 지수선물 = 주간, CM 야간선물), 종목코드, 조회 시작·종료일, 기간 D.
 * 한 번에 최대 100건이며, 이 클래스는 연속조회를 하지 않는다. 호출하는 쪽이 100건보다 짧은 구간만 요청한다.
 *
 * <p>가정(첫 실호출로 확인한다): 종목코드에 우리 DB 의 단축코드(A01612 형식)를 그대로 보내면 받아진다(명세 예시는 옛 형식 101S06),
 * CM(야간) 시장 코드로도 같은 방식으로 받아진다, 일봉은 주간장 기준이다, 쿼리 이름은 명세 표의 대문자 그대로 보내도 된다.
 *
 * <p>응답 {@code output2} 의 행마다 {@link DailyBar} 로 바꾼다. 시가·고가·저가·종가가 모두 0 인 행은 거래가 없던 날로 보고 버린다
 * (가정). 숫자가 아닌 값이 오면 조용히 넘기지 않고 실패한다. 로그·예외 메시지에는 키·시크릿·토큰 값을 쓰지 않는다.
 */
public final class KisDailyBarClient {

    private static final Logger LOG = LoggerFactory.getLogger(KisDailyBarClient.class);
    public static final String TR_ID = "FHKIF03020100";
    static final String PATH = "/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 시장 분류 코드. F 는 지수선물(주간), CM 은 야간선물. */
    public static final String MARKET_DAY = "F";
    public static final String MARKET_NIGHT = "CM";

    private final OkHttpClient http;
    private final HttpUrl endpoint;
    private final KisCredentials credentials;
    private final KisAccessTokenClient tokens;
    private final ObjectMapper json = new ObjectMapper();

    public KisDailyBarClient(OkHttpClient http, String restBaseUrl, KisCredentials credentials,
                             KisAccessTokenClient tokens) {
        this.http = http;
        String base = restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
        this.endpoint = HttpUrl.get(base + PATH);
        this.credentials = credentials;
        this.tokens = tokens;
    }

    /**
     * @param marketCode {@link #MARKET_DAY} 또는 {@link #MARKET_NIGHT}
     * @param symbol     종목 단축코드(근월물)
     * @param from       조회 시작일(포함)
     * @param to         조회 종료일(포함)
     * @return 영업일 오름차순. 거래가 없던 행은 빠진다
     */
    public List<DailyBar> fetch(String marketCode, String symbol, LocalDate from, LocalDate to) {
        HttpUrl url = endpoint.newBuilder()
                .addQueryParameter("FID_COND_MRKT_DIV_CODE", marketCode)
                .addQueryParameter("FID_INPUT_ISCD", symbol)
                .addQueryParameter("FID_INPUT_DATE_1", YYYYMMDD.format(from))
                .addQueryParameter("FID_INPUT_DATE_2", YYYYMMDD.format(to))
                .addQueryParameter("FID_PERIOD_DIV_CODE", "D")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .get()
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + tokens.accessToken())
                .header("appkey", credentials.appKey())
                .header("appsecret", credentials.appSecret())
                .header("tr_id", TR_ID)
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                if (res.code() == 401 || res.code() == 403) {
                    tokens.invalidate();
                }
                throw new IllegalStateException("daily bar request failed with HTTP " + res.code());
            }
            List<DailyBar> bars = parse(res.body().string());
            LOG.info("daily bars {} {} {}..{}: {} rows", marketCode, symbol, from, to, bars.size());
            return bars;
        } catch (IOException e) {
            throw new IllegalStateException("daily bar request failed: " + e.getClass().getSimpleName());
        }
    }

    List<DailyBar> parse(String body) throws IOException {
        JsonNode root = json.readTree(body);
        String rtCd = root.path("rt_cd").asText("");
        if (!rtCd.equals("0")) {
            throw new IllegalStateException("daily bar response rt_cd=" + rtCd + " msg_cd=" + root.path("msg_cd").asText(""));
        }
        List<DailyBar> out = new ArrayList<>();
        for (JsonNode row : root.path("output2")) {
            String date = row.path("stck_bsop_date").asText("").trim();
            if (date.isEmpty()) {
                continue;
            }
            BigDecimal open = decimal(row, "futs_oprc");
            BigDecimal high = decimal(row, "futs_hgpr");
            BigDecimal low = decimal(row, "futs_lwpr");
            BigDecimal close = decimal(row, "futs_prpr");
            if (open.signum() == 0 && high.signum() == 0 && low.signum() == 0 && close.signum() == 0) {
                continue;
            }
            out.add(new DailyBar(parseDate(date), open, high, low, close, decimal(row, "acml_vol").longValueExact(),
                    decimal(row, "acml_tr_pbmn"), row.path("mod_yn").asText("").trim()));
        }
        out.sort(Comparator.comparing(DailyBar::tradeDate));
        return out;
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s, YYYYMMDD);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("daily bar response has a bad stck_bsop_date");
        }
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        String s = row.path(field).asText("").trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("daily bar response has a non-numeric " + field);
        }
    }
}
