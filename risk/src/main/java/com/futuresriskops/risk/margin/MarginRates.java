package com.futuresriskops.risk.margin;

import java.math.BigDecimal;

/**
 * 증거금률(%). 거래소가 거의 매달 바꾸므로 코드에 두지 않고 설정({@code risk.margin.*})에서 받는다.
 *
 * @param initialPercent     위탁증거금률(개시증거금률). 주문할 때 필요한 비율
 * @param maintenancePercent 유지증거금률. 평가예탁금이 이 아래로 가면 마진콜
 */
public record MarginRates(BigDecimal initialPercent, BigDecimal maintenancePercent) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public MarginRates {
        if (initialPercent == null || maintenancePercent == null) {
            throw new IllegalArgumentException("initial and maintenance rates are required");
        }
        if (maintenancePercent.signum() <= 0 || initialPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("rates must be in (0, 100] percent");
        }
        if (maintenancePercent.compareTo(initialPercent) > 0) {
            throw new IllegalArgumentException("maintenance rate must not exceed the initial rate");
        }
    }
}
