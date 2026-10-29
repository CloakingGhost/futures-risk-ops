package com.futuresriskops.collector;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.SessionCalendarYaml;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.collector.store.TradeDateBackfill;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시세 수집기. 시작할 때 Flyway 로 DB 를 맞추고(마이그레이션 파일은 db 모듈),
 * 웹소켓으로 H0IFCNT0 체결을 받아 tick_trade 에 배치로 쌓는다. 장 시간은 세션 캘린더 YAML 이 정한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorApplication {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorApplication.class);

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
    SessionCalendar sessionCalendar(CollectorProperties props, ResourceLoader resources) {
        Resource r = resources.getResource(props.session().calendar());
        try (InputStream in = r.getInputStream()) {
            SessionCalendar c = SessionCalendarYaml.read(in);
            LOG.info("session calendar {}: {}", props.session().calendar(), c.describe());
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read session calendar " + props.session().calendar(), e);
        }
    }

    @Bean
    TickTradeRepository tickTradeRepository(JdbcTemplate jdbc) {
        return new TickTradeRepository(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "collector", name = "backfill-trade-dates", havingValue = "true")
    ApplicationRunner tradeDateBackfill(JdbcTemplate jdbc, SessionCalendar calendar) {
        return args -> new TradeDateBackfill(jdbc).run(calendar);
    }

    @Bean
    @ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
    IngestService ingestService(CollectorProperties props, SessionCalendar calendar, TickTradeRepository repository,
                                OkHttpClient http) {
        return new IngestService(props, calendar, repository::insertBatch, http, System.getenv(), Clock.systemUTC());
    }
}
