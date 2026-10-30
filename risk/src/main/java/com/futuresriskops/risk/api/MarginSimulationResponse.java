package com.futuresriskops.risk.api;

import com.futuresriskops.risk.margin.MarginResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/** 가상 포지션 평가. 금액은 원 단위. */
@Schema(description = "가상 포지션 평가 결과. 금액은 원")
public record MarginSimulationResponse(
        @Schema(description = "평가에 쓴 가격") BigDecimal price,
        @Schema(description = "REQUEST(요청 값) 또는 LATEST_TRADE(최신 체결)") String priceSource,
        @Schema(description = "LATEST_TRADE 일 때 그 체결의 종목. REQUEST 면 null") String symbol,
        @Schema(description = "LATEST_TRADE 일 때 그 체결 시각. REQUEST 면 null") Instant priceAt,
        @Schema(description = "위탁증거금률 %") BigDecimal initialRate,
        @Schema(description = "유지증거금률 %") BigDecimal maintenanceRate,
        MarginResult result) {
}
