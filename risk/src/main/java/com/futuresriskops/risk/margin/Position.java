package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/**
 * 사용자가 넣은 가상 포지션. 계좌와 연동하지 않는다.
 *
 * @param side      매수·매도
 * @param contracts 계약 수(1 이상)
 * @param basePrice 손익 기준가. 처음에는 진입가, 일일정산 뒤에는 그날 정산가
 */
public record Position(Side side, int contracts, BigDecimal basePrice) {

    public Position {
        if (side == null) {
            throw new IllegalArgumentException("side is required");
        }
        if (contracts < 1) {
            throw new IllegalArgumentException("contracts must be at least 1");
        }
        if (basePrice == null || basePrice.signum() <= 0) {
            throw new IllegalArgumentException("basePrice must be positive");
        }
    }
}
