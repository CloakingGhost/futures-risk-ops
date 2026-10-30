package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/**
 * 일일정산 한 번의 결과.
 *
 * @param variation 정산금(전 기준가 대비 손익). 예탁금에 더한다
 * @param deposit   정산 뒤 예탁금
 * @param next      기준가를 정산가로 바꾼 포지션. 다음 날 손익은 여기서 잰다
 */
public record Settlement(BigDecimal variation, BigDecimal deposit, Position next) {
}
