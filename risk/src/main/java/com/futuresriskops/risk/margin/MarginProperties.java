package com.futuresriskops.risk.margin;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 증거금 설정(application.yml 의 {@code risk.margin.*}). 값과 출처는 application.yml 주석에 적는다.
 *
 * @param initialRate     위탁증거금률 %
 * @param maintenanceRate 유지증거금률 %
 * @param multiplier      거래승수(원). 없으면 250,000
 * @param tickSize        호가 단위. 없으면 0.05
 */
@ConfigurationProperties(prefix = "risk.margin")
public record MarginProperties(BigDecimal initialRate, BigDecimal maintenanceRate, BigDecimal multiplier,
                               BigDecimal tickSize) {

    public MarginCalculator calculator() {
        ContractSpec spec = new ContractSpec(
                multiplier != null ? multiplier : ContractSpec.KOSPI200_FUTURES.multiplier(),
                tickSize != null ? tickSize : ContractSpec.KOSPI200_FUTURES.tickSize());
        return new MarginCalculator(spec, new MarginRates(initialRate, maintenanceRate));
    }
}
