package com.futuresriskops.collector.kis;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * H0IFASP0(지수선물 실시간호가) 한 건에서 적재에 쓰는 값. 목록은 1~5단계, 0번이 최우선 호가다.
 *
 * @param symbol        선물 단축 종목코드(futs_shrn_iscd)
 * @param bsopHour      호가 시각(bsop_hour, KST)
 * @param askPrices     매도호가 1~5(futs_askp1~5)
 * @param bidPrices     매수호가 1~5(futs_bidp1~5)
 * @param askQtys       매도호가 잔량 1~5(askp_rsqn1~5)
 * @param bidQtys       매수호가 잔량 1~5(bidp_rsqn1~5)
 * @param totalAskQty   총 매도호가 잔량(total_askp_rsqn)
 * @param totalBidQty   총 매수호가 잔량(total_bidp_rsqn)
 */
public record H0ifasp0Quote(String symbol, LocalTime bsopHour, List<BigDecimal> askPrices,
                            List<BigDecimal> bidPrices, List<Integer> askQtys, List<Integer> bidQtys,
                            long totalAskQty, long totalBidQty) {

    public H0ifasp0Quote {
        askPrices = List.copyOf(askPrices);
        bidPrices = List.copyOf(bidPrices);
        askQtys = List.copyOf(askQtys);
        bidQtys = List.copyOf(bidQtys);
    }
}
