package com.futuresriskops.collector.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 테스트용 캘린더. 시나리오 테스트는 고정 YAML 을 쓰고, 실제 config/session-calendar.yaml 은 읽히는지만 본다. */
public final class TestCalendars {

    /** 시나리오 테스트 기준. 휴장일·특수 개장은 테스트를 위해 정한 값이다. */
    public static final String FIXED = """
            timezone: Asia/Seoul
            regular:
              day:   { open: "08:45", close: "15:45" }
              night: { open: "18:00", close: "06:00" }
            night_trade_date: next_trading_day
            holidays:
              - { date: 2026-12-25, name: test holiday }
            overrides:
              - date: 2026-11-19
                reason: test late open
                day: { open: "09:45", close: "16:45" }
              - date: 2026-11-20
                reason: test no night session
                night: closed
            """;

    private TestCalendars() {
    }

    public static SessionCalendar fromYaml(String yaml) {
        return SessionCalendarYaml.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    public static SessionCalendar fixed() {
        return fromYaml(FIXED);
    }

    /** 저장소의 config/session-calendar.yaml (빌드가 클래스패스에 넣는다). */
    public static SessionCalendar standard() {
        try (InputStream in = TestCalendars.class.getResourceAsStream("/session-calendar.yaml")) {
            if (in == null) {
                throw new IllegalStateException("session-calendar.yaml is not on the classpath");
            }
            return SessionCalendarYaml.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
