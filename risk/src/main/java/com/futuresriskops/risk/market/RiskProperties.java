package com.futuresriskops.risk.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * risk 가 읽을 데이터 출처(application.yml 의 {@code risk.source}). 없으면 SIMULATOR.
 */
@ConfigurationProperties(prefix = "risk")
public record RiskProperties(MarketSource source) {

    public MarketSource sourceOrDefault() {
        return source != null ? source : MarketSource.SIMULATOR;
    }
}
