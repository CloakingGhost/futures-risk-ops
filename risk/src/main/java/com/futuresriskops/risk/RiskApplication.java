package com.futuresriskops.risk;

import com.futuresriskops.risk.market.RiskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 리스크 계산 API. 증거금 계산은 {@code margin}, collector 가 쌓은 시세 읽기는 {@code market} 패키지다.
 * DB 는 읽기만 하고 마이그레이션은 하지 않는다(risk/CLAUDE.md).
 */
@SpringBootApplication
@EnableConfigurationProperties(RiskProperties.class)
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
