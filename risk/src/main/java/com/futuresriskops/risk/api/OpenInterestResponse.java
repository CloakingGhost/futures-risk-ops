package com.futuresriskops.risk.api;

import com.futuresriskops.risk.market.DailyOpenInterest;
import com.futuresriskops.risk.market.MarketSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 거래일별 미결제약정, 최신이 먼저. */
@Schema(description = "거래일별 미결제약정과 전 거래일 대비 증감, 최신이 먼저")
public record OpenInterestResponse(String symbol, MarketSource source, List<DailyOpenInterest> days) {
}
