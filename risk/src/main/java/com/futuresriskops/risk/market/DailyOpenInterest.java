package com.futuresriskops.risk.market;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 거래일 하나의 미결제약정.
 *
 * @param openInterest 그 거래일 마지막 체결의 미결제약정 수량
 * @param change       전 거래일 대비 증감. 비교할 전 거래일이 없으면 null
 * @param lastTradeAt  그 값을 읽은 체결 시각
 */
public record DailyOpenInterest(LocalDate tradeDate, long openInterest, Long change, Instant lastTradeAt) {
}
