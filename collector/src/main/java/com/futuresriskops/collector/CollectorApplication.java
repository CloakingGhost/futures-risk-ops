package com.futuresriskops.collector;

import com.futuresriskops.collector.instrument.InstrumentRepository;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.SessionCalendarYaml;
import com.futuresriskops.collector.store.TickQuoteRepository;
import com.futuresriskops.collector.store.TickTradeRepository;
import com.futuresriskops.collector.store.TradeDateBackfill;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * 웹소켓으로 체결(주간 H0IFCNT0, 야간 H0MFCNT0)과 호가(H0IFASP0)를 받아 tick_trade·tick_quote 에 배치로 쌓는다.
 * 장 시간은 세션 캘린더 YAML 이, 구독할 근월물은 결제월 규칙과 KIS 종목 마스터가 정한다.
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
    TickQuoteRepository tickQuoteRepository(JdbcTemplate jdbc, TickTradeRepository trades) {
        return new TickQuoteRepository(jdbc, trades);
    }

    @Bean
    InstrumentRepository instrumentRepository(JdbcTemplate jdbc, SessionCalendar calendar) {
        return new InstrumentRepository(jdbc, calendar);
    }

    /**
     * 수집기 시계. collector.clock-start 가 있으면 그 한국 시각부터 흐르는 시계다(시뮬레이터로 장 밖에서 세션·롤오버를 볼 때).
     * KIS 에 붙을 때는 실제 시각과 다르면 거래일·세션이 틀리므로 거절한다.
     */
    static Clock collectorClock(CollectorProperties props, SessionCalendar calendar, Clock system) {
        if (props.clockStart() == null) {
            return system;
        }
        if (props.approval().mode() == CollectorProperties.Approval.Mode.KIS) {
            throw new IllegalStateException("collector.clock-start is for the simulator only; remove it for KIS profiles");
        }
        Instant start = props.clockStart().atZone(calendar.zone()).toInstant();
        LOG.warn("collector clock starts at {} (simulator only, offset from the real time)", props.clockStart());
        return Clock.offset(system, Duration.between(system.instant(), start));
    }

    @Bean
    @ConditionalOnProperty(prefix = "collector", name = "backfill-trade-dates", havingValue = "true")
    ApplicationRunner tradeDateBackfill(JdbcTemplate jdbc, SessionCalendar calendar) {
        return args -> new TradeDateBackfill(jdbc).run(calendar);
    }

    @Bean
    @ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
    IngestService ingestService(CollectorProperties props, SessionCalendar calendar, TickTradeRepository repository,
                                TickQuoteRepository quotes, InstrumentRepository instruments, OkHttpClient http) {
        return new IngestService(props, calendar, repository::insertBatch, quotes::insertBatch, instruments, null,
                http, System.getenv(), collectorClock(props, calendar, Clock.systemUTC()));
    }
}
