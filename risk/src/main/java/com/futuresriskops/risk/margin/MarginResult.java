package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/**
 * 포지션 평가 결과. 금액은 원 단위 정수다.
 *
 * @param pnl                손익(기준가 대비)
 * @param initialMargin      위탁증거금(현재가 기준)
 * @param maintenanceMargin  유지증거금(현재가 기준)
 * @param equity             평가예탁금 = 예탁금 + 손익
 * @param marginCall         평가예탁금 < 유지증거금
 * @param callAmount         마진콜이면 위탁증거금 - 평가예탁금, 아니면 0
 * @param marginCallPrice    매수는 이 가격 아래로, 매도는 이 가격 위로 가면 마진콜. 매수에서 그런 가격이 없으면 null
 * @param pointsToMarginCall 현재가에서 마진콜 가격까지 남은 포인트. 이미 마진콜이면 0, 마진콜 가격이 없으면 null
 */
public record MarginResult(BigDecimal pnl, BigDecimal initialMargin, BigDecimal maintenanceMargin, BigDecimal equity,
                           boolean marginCall, BigDecimal callAmount, BigDecimal marginCallPrice,
                           BigDecimal pointsToMarginCall) {
}
