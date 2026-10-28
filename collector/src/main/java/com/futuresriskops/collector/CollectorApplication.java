package com.futuresriskops.collector;

import com.futuresriskops.collector.store.TickTradeRepository;
import java.time.Clock;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시세 수집기. 시작할 때 Flyway 로 DB 를 맞추고(마이그레이션 파일은 db 모듈),
 * 웹소켓으로 H0IFCNT0 체결을 받아 tick_trade 에 배치로 쌓는다.
 */
@SpringBootApplication
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }

    @Bean
    OkHttpClient okHttpClient() {
        // 읽기 제한을 두지 않는다: 장이 조용하면 체결 없이 PINGPONG 만 오는 시간이 길다
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ZERO)
                .build();
    }

    @Bean
    TickTradeRepository tickTradeRepository(JdbcTemplate jdbc) {
        return new TickTradeRepository(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
    IngestService ingestService(CollectorProperties props, TickTradeRepository repository, OkHttpClient http) {
        return new IngestService(props, repository, http, System.getenv(), Clock.systemUTC());
    }
}
