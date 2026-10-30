package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/**
 * 상품 규격.
 *
 * @param multiplier 1포인트가 몇 원인지(거래승수)
 * @param tickSize   호가 단위(포인트)
 */
public record ContractSpec(BigDecimal multiplier, BigDecimal tickSize) {

    /** 코스피200 선물: 승수 250,000원, 틱 0.05(= 12,500원). */
    public static final ContractSpec KOSPI200_FUTURES = new ContractSpec(new BigDecimal("250000"), new BigDecimal("0.05"));

    public ContractSpec {
        if (multiplier == null || multiplier.signum() <= 0) {
            throw new IllegalArgumentException("multiplier must be positive");
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            throw new IllegalArgumentException("tickSize must be positive");
        }
    }
}
