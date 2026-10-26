package com.futuresriskops.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 리스크 계산 API. Day 0 에는 빈 뼈대이고, 계산과 REST 는 이후 날짜에 추가한다. */
@SpringBootApplication
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
