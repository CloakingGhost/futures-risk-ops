package com.futuresriskops.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 리스크 계산 API. 증거금 계산은 {@code margin} 패키지(Day 4), REST 는 이후 단계에서 더한다. */
@SpringBootApplication
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
