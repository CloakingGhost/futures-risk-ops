package com.futuresriskops.risk.api;

import com.futuresriskops.risk.margin.MarginCalculator;
import com.futuresriskops.risk.margin.Position;
import com.futuresriskops.risk.market.LatestTrade;
import com.futuresriskops.risk.market.LatestTradeRepository;
import com.futuresriskops.risk.market.MarketSource;
import com.futuresriskops.risk.market.OpenInterestHistory;
import com.futuresriskops.risk.market.RiskProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리스크 REST API 3개. 읽는 데이터 출처는 설정 {@code risk.source} 하나이고, 응답마다 계산에 쓴 데이터의 시각을 넣는다.
 * 결과는 사용자가 넣은 포지션을 계산한 값뿐이다(매수·매도 판단은 하지 않는다).
 */
@RestController
@RequestMapping("/api/risk")
@Tag(name = "risk", description = "베이시스, 미결제 일별 변화, 가상 포지션 증거금")
public class RiskController {

    static final int MAX_DAYS = 60;

    private final LatestTradeRepository trades;
    private final OpenInterestHistory openInterest;
    private final MarginCalculator calculator;
    private final MarketSource source;

    public RiskController(LatestTradeRepository trades, OpenInterestHistory openInterest, MarginCalculator calculator,
                          RiskProperties props) {
        this.trades = trades;
        this.openInterest = openInterest;
        this.calculator = calculator;
        this.source = props.sourceOrDefault();
    }

    @GetMapping("/basis")
    @Operation(summary = "최신 체결 기준 베이시스·이론가·괴리율과 거꾸로 구한 코스피200 지수")
    public BasisResponse basis(@Parameter(description = "종목코드. 없으면 최근월물(마스터) 또는 가장 최근 체결 종목")
                               @RequestParam(required = false) String symbol) {
        return BasisResponse.of(latest(symbol));
    }

    @GetMapping("/open-interest/daily")
    @Operation(summary = "거래일별 미결제약정과 전 거래일 대비 증감")
    public OpenInterestResponse openInterestDaily(
            @Parameter(description = "종목코드. 없으면 기본 종목") @RequestParam(required = false) String symbol,
            @Parameter(description = "최근 몇 거래일(1~60)") @RequestParam(defaultValue = "5") int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        String s = resolve(symbol);
        return new OpenInterestResponse(s, source, openInterest.daily(s, source, days));
    }

    @PostMapping("/margin/simulate")
    @Operation(summary = "가상 포지션의 손익, 위탁·유지증거금, 마진콜 여부와 마진콜까지 남은 폭")
    public MarginSimulationResponse simulate(@RequestBody MarginSimulationRequest req) {
        if (req.deposit() == null || req.deposit().signum() < 0) {
            throw new IllegalArgumentException("deposit is required and must not be negative");
        }
        Position position = new Position(req.side(), req.contracts(), req.basePrice());
        BigDecimal price = req.price();
        String priceSource = "REQUEST";
        String symbol = null;
        Instant priceAt = null;
        if (price == null) {
            LatestTrade t = latest(req.symbol());
            price = t.price();
            priceSource = "LATEST_TRADE";
            symbol = t.symbol();
            priceAt = t.tradedAt();
        } else if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        return new MarginSimulationResponse(price, priceSource, symbol, priceAt,
                calculator.rates().initialPercent(), calculator.rates().maintenancePercent(),
                calculator.evaluate(position, req.deposit(), price));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoDataException.class)
    ResponseEntity<Map<String, String>> notFound(NoDataException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    private LatestTrade latest(String symbol) {
        String s = resolve(symbol);
        return trades.latest(s, source).orElseThrow(
                () -> new NoDataException("no trades for " + s + " from source " + source));
    }

    private String resolve(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return symbol.trim();
        }
        return trades.defaultSymbol(source).orElseThrow(
                () -> new NoDataException("no trades from source " + source + " yet"));
    }
}
