package com.futuresriskops.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 시세 수집기. Day 0 에는 시작할 때 Flyway 가 DB 테이블을 만드는 것까지만 한다.
 * 웹소켓 수집은 이후 날짜에 추가한다.
 */
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
