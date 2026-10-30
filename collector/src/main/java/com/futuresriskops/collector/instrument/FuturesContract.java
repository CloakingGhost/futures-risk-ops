package com.futuresriskops.collector.instrument;

import java.time.YearMonth;

/**
 * 코스피200 선물 한 종목(마스터 파일 한 줄).
 *
 * @param symbol        단축코드(구독 tr_key, 예: A01612)
 * @param standardCode  표준코드(예: KR4A016C0004)
 * @param name          종목명(마스터 원문, 예: "F 202612")
 * @param contractMonth 결제월
 * @param monthClass    마스터의 월물구분코드(1 최근월물, 2 차근월물 ...). 규칙으로 만든 종목은 0
 */
public record FuturesContract(String symbol, String standardCode, String name, YearMonth contractMonth,
                              int monthClass) {

    /** 마스터 없이 규칙({@link FuturesCode})으로 만든 종목. */
    public static FuturesContract fromRule(YearMonth m) {
        return new FuturesContract(FuturesCode.forMonth(m), null, null, m, 0);
    }
}
