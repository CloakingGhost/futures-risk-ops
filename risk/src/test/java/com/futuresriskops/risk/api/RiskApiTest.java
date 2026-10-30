package com.futuresriskops.risk.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.futuresriskops.risk.market.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 앱 전체를 띄우고 실제 PostgreSQL(V1~V6)에 합성 체결을 넣은 뒤 API 3개와 health 를 부른다. Docker 가 없으면 건너뛴다.
 * 증거금률은 application.yml 값(21.750%, 14.500%)이다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "risk.source=KIS_PAPER")
@AutoConfigureMockMvc
class RiskApiTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgresTestSupport.container();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestSupport.url(PG));
        r.add("spring.datasource.username", () -> PostgresTestSupport.username(PG));
        r.add("spring.datasource.password", () -> PostgresTestSupport.password(PG));
    }

    @Autowired
    MockMvc mvc;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(PostgresTestSupport.migrated(PG));
        jdbc.update("DELETE FROM tick_trade");
        jdbc.update("DELETE FROM tick_quote");
        jdbc.update("DELETE FROM instrument");
    }

    private void trade(String at, String date, String session, String price, long acc, long oi, String basis,
                       String source) {
        PostgresTestSupport.trade(jdbc, "A01612", at, date, session, price, acc, oi, basis, source);
    }

    @Test
    void basisReturnsTheLatestTradeWithItsTimeAndTheImpliedIndex() throws Exception {
        trade("2026-10-30T01:00:00Z", "2026-10-30", "DAY", "412.50", 10, 250_000, "0.40", "KIS_PAPER");
        trade("2026-10-30T01:00:05Z", "2026-10-30", "DAY", "412.60", 11, 250_001, "0.45", "KIS_PAPER");
        trade("2026-10-30T01:00:09Z", "2026-10-30", "DAY", "999.00", 12, 1, "0.45", "SIMULATOR");

        mvc.perform(get("/api/risk/basis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("A01612"))
                .andExpect(jsonPath("$.source").value("KIS_PAPER"))
                .andExpect(jsonPath("$.tradedAt").value("2026-10-30T01:00:05Z"))
                .andExpect(jsonPath("$.futuresPrice").value(412.60))
                .andExpect(jsonPath("$.marketBasis").value(0.45))
                .andExpect(jsonPath("$.impliedIndex").value(412.15))
                .andExpect(jsonPath("$.session").value("DAY"));
    }

    @Test
    void basisFieldsAreNullForRowsBeforeV6() throws Exception {
        trade("2026-10-29T01:00:00Z", "2026-10-29", "DAY", "411.00", 10, 250_000, null, "KIS_PAPER");
        mvc.perform(get("/api/risk/basis").param("symbol", "A01612"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.futuresPrice").value(411.00))
                .andExpect(jsonPath("$.marketBasis").value(nullValue()))
                .andExpect(jsonPath("$.impliedIndex").value(nullValue()));
    }

    @Test
    void noTradesIsNotFoundWithAMessage() throws Exception {
        mvc.perform(get("/api/risk/basis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no trades from source KIS_PAPER yet"));
        mvc.perform(get("/api/risk/basis").param("symbol", "A09999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openInterestDailyListsTradingDaysWithChanges() throws Exception {
        trade("2026-10-30T06:44:00Z", "2026-10-30", "DAY", "412.00", 50, 250_300, "0.30", "KIS_PAPER");
        trade("2026-10-29T06:44:00Z", "2026-10-29", "DAY", "410.00", 40, 251_000, "0.30", "KIS_PAPER");
        trade("2026-10-28T06:44:00Z", "2026-10-28", "DAY", "409.00", 30, 249_500, "0.30", "KIS_PAPER");

        mvc.perform(get("/api/risk/open-interest/daily").param("days", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("A01612"))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].tradeDate").value("2026-10-30"))
                .andExpect(jsonPath("$.days[0].openInterest").value(250_300))
                .andExpect(jsonPath("$.days[0].change").value(-700))
                .andExpect(jsonPath("$.days[0].lastTradeAt").value("2026-10-30T06:44:00Z"))
                .andExpect(jsonPath("$.days[1].change").value(1_500));
    }

    @Test
    void openInterestRejectsAnOutOfRangeDayCount() throws Exception {
        trade("2026-10-30T06:44:00Z", "2026-10-30", "DAY", "412.00", 50, 250_300, "0.30", "KIS_PAPER");
        mvc.perform(get("/api/risk/open-interest/daily").param("days", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/risk/open-interest/daily").param("days", "61")).andExpect(status().isBadRequest());
    }

    @Test
    void simulateWithARequestPriceDoesNotNeedTrades() throws Exception {
        // 매수 1계약 400.00 → 398.50: -1.5포인트 x 250,000 = -375,000
        mvc.perform(post("/api/risk/margin/simulate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"side":"LONG","contracts":1,"basePrice":400.00,"deposit":30000000,"price":398.50}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceSource").value("REQUEST"))
                .andExpect(jsonPath("$.priceAt").value(nullValue()))
                .andExpect(jsonPath("$.initialRate").value(21.750))
                .andExpect(jsonPath("$.result.pnl").value(-375000))
                .andExpect(jsonPath("$.result.equity").value(29625000))
                .andExpect(jsonPath("$.result.marginCall").value(false));
    }

    @Test
    void simulateWithoutAPriceUsesTheLatestTradeAndSaysWhen() throws Exception {
        trade("2026-10-30T01:00:05Z", "2026-10-30", "DAY", "398.50", 11, 250_001, "0.45", "KIS_PAPER");
        mvc.perform(post("/api/risk/margin/simulate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"side":"LONG","contracts":1,"basePrice":400.00,"deposit":30000000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(398.50))
                .andExpect(jsonPath("$.priceSource").value("LATEST_TRADE"))
                .andExpect(jsonPath("$.symbol").value("A01612"))
                .andExpect(jsonPath("$.priceAt").value("2026-10-30T01:00:05Z"))
                .andExpect(jsonPath("$.result.pnl").value(-375000));
    }

    @Test
    void simulateRejectsBadPositions() throws Exception {
        String[] bodies = {
                "{\"side\":\"LONG\",\"contracts\":0,\"basePrice\":400,\"deposit\":1,\"price\":400}",
                "{\"contracts\":1,\"basePrice\":400,\"deposit\":1,\"price\":400}",
                "{\"side\":\"LONG\",\"contracts\":1,\"basePrice\":400,\"price\":400}",
                "{\"side\":\"LONG\",\"contracts\":1,\"basePrice\":400,\"deposit\":-1,\"price\":400}",
                "{\"side\":\"LONG\",\"contracts\":1,\"basePrice\":400,\"deposit\":1,\"price\":0}",
                "{\"side\":\"BUY\",\"contracts\":1,\"basePrice\":400,\"deposit\":1,\"price\":400}",
        };
        for (String body : bodies) {
            mvc.perform(post("/api/risk/margin/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void healthAndApiDocsAreOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/risk/basis']").exists())
                .andExpect(jsonPath("$.paths['/api/risk/open-interest/daily']").exists())
                .andExpect(jsonPath("$.paths['/api/risk/margin/simulate']").exists());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
