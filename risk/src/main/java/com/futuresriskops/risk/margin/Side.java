package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/** 포지션 방향. 매수는 가격이 오르면 이익, 매도는 내리면 이익이다. */
public enum Side {
    LONG(BigDecimal.ONE),
    SHORT(BigDecimal.ONE.negate());

    private final BigDecimal sign;

    Side(BigDecimal sign) {
        this.sign = sign;
    }

    /** 손익 부호. 매수 +1, 매도 -1. */
    public BigDecimal sign() {
        return sign;
    }
}
