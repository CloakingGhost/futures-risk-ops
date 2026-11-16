package com.futuresriskops.collector.rest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KIS 일봉 한 건(선물옵션기간별시세 응답 output2 한 행). KIS 가 준 값을 그대로 담고 다시 계산하지 않는다.
 *
 * @param tradeDate  영업 일자(stck_bsop_date)
 * @param open       시가(futs_oprc)
 * @param high       최고가(futs_hgpr)
 * @param low        최저가(futs_lwpr)
 * @param close      종가. 응답의 현재가(futs_prpr)이며, 과거 일자는 그날 종가라고 본다(가정: 문서는 '현재가'라고만 적음)
 * @param volume     누적 거래량(acml_vol)
 * @param tradeValue 누적 거래 대금(acml_tr_pbmn)
 * @param modYn      변경 여부(mod_yn). 의미는 문서에 없어 값만 보관한다
 */
public record DailyBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                       long volume, BigDecimal tradeValue, String modYn) {
}
