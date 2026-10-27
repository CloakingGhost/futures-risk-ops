package com.futuresriskops.collector.kis;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * H0IFCNT0 한 건에서 수집에 쓰는 값. 프레임에는 날짜가 없어서 시각만 있다.
 *
 * @param symbol       선물 단축 종목코드(futs_shrn_iscd)
 * @param bsopHour     체결 시각(bsop_hour, KST)
 * @param price        현재가(futs_prpr)
 * @param qty          이번 체결 수량(last_cnqn)
 * @param accVolume    누적 거래량(acml_vol). 중복 체결을 가리는 키로 쓴다
 * @param openInterest 미결제약정 수량(hts_otst_stpl_qty)
 */
public record H0ifcnt0Trade(String symbol, LocalTime bsopHour, BigDecimal price, int qty, long accVolume,
                            long openInterest) {
}
