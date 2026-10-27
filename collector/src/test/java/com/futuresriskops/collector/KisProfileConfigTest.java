package com.futuresriskops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** 프로필 파일이 가리키는 도메인이 실전·모의투자에서 섞이지 않는지만 본다. 접속하지 않는다. */
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
        assertEquals("KIS", p.getProperty("collector.source"));
        assertEquals("KIS_PAPER_APP_KEY", p.getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_PAPER_APP_SECRET", p.getProperty("collector.approval.app-secret-env"));
    }

    @Test
    void realProfileUsesTheRealWebsocketAndKeepsTheDefaultRestDomain() {
        Properties p = load("application-kis.yml");
        assertEquals("ws://ops.koreainvestment.com:21000/tryitout", p.getProperty("collector.ws-url"));
        assertEquals("KIS", p.getProperty("collector.approval.mode"));
        assertEquals("https://openapi.koreainvestment.com:9443",
                load("application.yml").getProperty("collector.approval.rest-base-url"));
        // 실전은 프로필에서 이름을 덮어쓰지 않고 기본(KIS_APP_*)을 쓴다
        assertEquals(null, p.getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_APP_KEY", load("application.yml").getProperty("collector.approval.app-key-env"));
        assertEquals("KIS_APP_SECRET", load("application.yml").getProperty("collector.approval.app-secret-env"));
    }
}
