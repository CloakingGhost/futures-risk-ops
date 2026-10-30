package com.futuresriskops.risk.api;

import com.futuresriskops.risk.market.LatestTrade;
import com.futuresriskops.risk.market.MarketSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 최신 체결 기준 베이시스. 값은 KIS 가 체결 메시지에 담아 보낸 것이고, V6 이전 행이면 null 이다. */
@Schema(description = "최신 체결 기준 베이시스. 베이시스·이론가·괴리율은 KIS 체결 메시지 값(없으면 null)")
public record BasisResponse(
        String symbol,
        MarketSource source,
        @Schema(description = "계산에 쓴 체결 시각. 오래됐으면 수집이 멈춘 것일 수 있다") Instant tradedAt,
        LocalDate tradeDate,
        @Schema(description = "DAY 또는 NIGHT. 야간장에는 주식시장이 닫혀 지수가 움직이지 않는다") String session,
        BigDecimal futuresPrice,
        @Schema(description = "시장 베이시스(mrkt_basis) = 선물가 - 코스피200 지수(가정)") BigDecimal marketBasis,
        @Schema(description = "선물가 - 시장 베이시스로 구한 코스피200 지수") BigDecimal impliedIndex,
        @Schema(description = "HTS 이론가(hts_thpr)") BigDecimal theoreticalPrice,
        @Schema(description = "괴리율 %(dprt)") BigDecimal disparityRate) {

    static BasisResponse of(LatestTrade t) {
        return new BasisResponse(t.symbol(), t.source(), t.tradedAt(), t.tradeDate(), t.session(), t.price(),
                t.marketBasis(), t.impliedIndex(), t.theoreticalPrice(), t.disparityRate());
    }
}
