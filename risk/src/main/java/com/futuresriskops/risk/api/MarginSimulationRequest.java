package com.futuresriskops.risk.api;

import com.futuresriskops.risk.margin.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 가상 포지션. 계좌와 연동하지 않고 사용자가 넣은 값으로만 계산한다. */
@Schema(description = "가상 포지션. 계좌와 연동하지 않는다")
public record MarginSimulationRequest(
        @Schema(description = "LONG(매수) 또는 SHORT(매도)", example = "LONG") Side side,
        @Schema(description = "계약 수", example = "1") int contracts,
        @Schema(description = "기준가: 진입가, 일일정산 뒤에는 직전 정산가", example = "400.00") BigDecimal basePrice,
        @Schema(description = "기준가 시점의 예탁금(원)", example = "30000000") BigDecimal deposit,
        @Schema(description = "평가할 가격. 없으면 그 종목의 최신 체결가를 쓴다", example = "398.50") BigDecimal price,
        @Schema(description = "price 가 없을 때 최신 체결가를 읽을 종목. 없으면 기본 종목") String symbol) {
}
