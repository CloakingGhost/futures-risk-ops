package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.instrument.SubscriptionPlanner;
import com.futuresriskops.collector.session.TestCalendars;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.io.ClassPathResource;

/** 프로필 파일이 가리키는 도메인이 실전·모의투자에서 섞이지 않는지, 설정이 CollectorProperties 로 묶이는지 본다. 접속하지 않는다. */
class KisProfileConfigTest {

    private static Properties load(String file) {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource(file));
        Properties p = y.getObject();
        assertNotNull(p, file);
        return p;
    }

    @Test
    void paperProfileUsesThePaperWebsocketAndRestDomains() {
        Properties p = load("application-kis-paper.yml");
        assertEquals("ws://ops.koreainvestment.com:31000/tryitout", p.getProperty("collector.ws-url"));
        assertEquals("https://openapivts.koreainvestment.com:29443", p.getProperty("collector.approval.rest-base-url"));
        assertEquals("KIS", p.getProperty("collector.approval.mode"));
        assertEquals("KIS_PAPER", p.getProperty("collector.source"));
        assertEquals("KIS_PAPER_APP_KEY", p.getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_PAPER_APP_SECRET", p.getProperty("collector.approval.app-secret-env"));
    }

    @Test
    void realProfileUsesTheRealWebsocketAndKeepsTheDefaultRestDomain() {
        Properties p = load("application-kis.yml");
        assertEquals("ws://ops.koreainvestment.com:21000/tryitout", p.getProperty("collector.ws-url"));
        assertEquals("KIS", p.getProperty("collector.approval.mode"));
        assertEquals("KIS", p.getProperty("collector.source"));
        assertEquals("https://openapi.koreainvestment.com:9443",
                load("application.yml").getProperty("collector.approval.rest-base-url"));
        // 실전은 프로필에서 이름을 덮어쓰지 않고 기본(KIS_APP_*)을 쓴다
        assertEquals(null, p.getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_APP_KEY", load("application.yml").getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_APP_SECRET", load("application.yml").getProperty("collector.approval.app-secret-env"));
    }

    private static CollectorProperties bind(Properties... layers) {
        Map<Object, Object> merged = new HashMap<>();
        for (Properties l : layers) {
            merged.putAll(l);
        }
        return new Binder(List.of(new MapConfigurationPropertySource(merged)), null,
                ApplicationConversionService.getSharedInstance())
                .bind("collector", CollectorProperties.class).get();
    }

    @Test
    void defaultsPickTheNearMonthWithQuotesAndOnlyKisProfilesDownloadTheMaster() {
        CollectorProperties sim = bind(load("application.yml"));
        assertEquals(SubscriptionPlanner.Mode.AUTO, sim.instruments().mode());
        assertTrue(sim.instruments().quotes());
        assertFalse(sim.instruments().master().download());
        assertEquals(LocalTime.of(7, 30), sim.instruments().master().refreshAt());
        assertNull(sim.clockStart());
        assertTrue(bind(load("application.yml"), load("application-kis.yml")).instruments().master().download());
        CollectorProperties paper = bind(load("application.yml"), load("application-kis-paper.yml"));
        assertTrue(paper.instruments().master().download());
        assertEquals(sim.instruments().master().url(), paper.instruments().master().url());
    }

    @Test
    void clockStartBindsAndIsRefusedForKis() {
        Properties extra = new Properties();
        extra.setProperty("collector.clock-start", "2026-12-10T15:43:00");
        CollectorProperties sim = bind(load("application.yml"), extra);
        assertEquals(LocalDateTime.of(2026, 12, 10, 15, 43), sim.clockStart());
        Clock c = CollectorApplication.collectorClock(sim, TestCalendars.standard(), Clock.systemUTC());
        assertEquals(2026, c.instant().atZone(ZoneId.of("Asia/Seoul")).getYear());
        assertEquals(12, c.instant().atZone(ZoneId.of("Asia/Seoul")).getMonthValue());
        CollectorProperties kis = bind(load("application.yml"), load("application-kis.yml"), extra);
        assertThrows(IllegalStateException.class,
                () -> CollectorApplication.collectorClock(kis, TestCalendars.standard(), Clock.systemUTC()));
    }
}
