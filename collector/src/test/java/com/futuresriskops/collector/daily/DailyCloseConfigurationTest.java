package com.futuresriskops.collector.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.CollectorProperties;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.store.TickTrade;
import com.futuresriskops.collector.store.TickTradeRepository;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 일봉 수집 빈이 설정대로 만들어지는지 본다. DB·KIS 에 접속하지 않는다. */
class DailyCloseConfigurationTest {

    private static CollectorProperties props(CollectorProperties.Approval.Mode mode) {
        return new CollectorProperties(true, TickTrade.Source.KIS, "ws://127.0.0.1:1/x", List.of(), null, null,
                new CollectorProperties.Session("classpath:session-calendar.yaml", false, Duration.ofMinutes(1)),
                Duration.ofSeconds(5), false,
                new CollectorProperties.Approval(mode, "https://127.0.0.1:1", "TEST_NO_SUCH_APP_KEY",
                        "TEST_NO_SUCH_APP_SECRET", null),
                null, null, null);
    }

    private ApplicationContextRunner runner(CollectorProperties.Approval.Mode mode) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DailyCloseConfiguration.class)
                .withBean(CollectorProperties.class, () -> props(mode))
                .withBean(DailyCloseProperties.class, () -> new DailyCloseProperties(true, LocalTime.of(16, 0),
                        Duration.ofMinutes(15), 5, List.of("F", "CM")))
                .withBean(OkHttpClient.class, OkHttpClient::new)
                .withBean(JdbcTemplate.class, DailyCloseConfigurationTest::unconnectedJdbc)
                .withBean(TickTradeRepository.class, () -> new TickTradeRepository(unconnectedJdbc()))
                .withBean(com.futuresriskops.collector.session.SessionCalendar.class, TestCalendars::fixed);
    }

    @Test
    void nothingIsCreatedUnlessEnabled() {
        runner(CollectorProperties.Approval.Mode.KIS).run(ctx -> {
            assertEquals(0, ctx.getBeanNamesForType(DailyCloseJob.class).length);
            assertEquals(0, ctx.getBeanNamesForType(DailyCloseScheduler.class).length);
        });
    }

    @Test
    void enablingItWithTheSimulatorSettingsFailsAtStartWithAClearMessage() {
        runner(CollectorProperties.Approval.Mode.NONE).withPropertyValues("collector.daily-close.enabled=true").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertTrue(rootMessage(ctx.getStartupFailure()).contains("KIS profile"), rootMessage(ctx.getStartupFailure()));
        });
    }

    @Test
    void enablingItWithoutTheKeyEnvironmentVariablesFailsNamingTheVariable() {
        runner(CollectorProperties.Approval.Mode.KIS).withPropertyValues("collector.daily-close.enabled=true").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            String msg = rootMessage(ctx.getStartupFailure());
            assertTrue(msg.contains("TEST_NO_SUCH_APP_KEY"), msg);
        });
    }

    /** 연결을 만들지 않는 JdbcTemplate(접속은 처음 쿼리를 보낼 때 하므로 여기서는 열리지 않는다). */
    private static JdbcTemplate unconnectedJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "u", "p"));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
