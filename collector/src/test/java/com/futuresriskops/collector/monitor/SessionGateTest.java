package com.futuresriskops.collector.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.session.TestCalendars;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * 세션 게이트: 언제 접속해 있어야 하는가. 마감 종가 단일가 체결은 마감 시각 정각에 찍히고, 실전에서 그 체결이 정각 0.2~1.6초
 * 전에 도착했다(2026-11-18 관찰). 그래서 마감 뒤에도 잠깐 더 붙어 있는다. 시각은 합성 캘린더(TestCalendars.fixed)의 값이다.
 */
class SessionGateTest {

    private static final Duration LEAD = Duration.ofMinutes(1);
    private static final Duration LINGER = Duration.ofSeconds(10);

    private static Instant kst(String dateTime) {
        return ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]").toInstant();
    }

    private final SessionGate gate = new SessionGate(TestCalendars.fixed(), true, LEAD, LINGER);

    @Test
    void staysConnectedForTheLingerAfterTheNightCloses() {
        assertTrue(gate.wantConnected(kst("2026-10-28T05:59:59")));
        assertTrue(gate.wantConnected(kst("2026-10-28T06:00:00")), "the closing tick is stamped exactly at the close");
        assertTrue(gate.wantConnected(kst("2026-10-28T06:00:09")));
        assertFalse(gate.wantConnected(kst("2026-10-28T06:00:10")), "the linger ends after 10 seconds");
    }

    @Test
    void staysConnectedForTheLingerAfterTheDayCloses() {
        assertTrue(gate.wantConnected(kst("2026-10-28T15:45:00")));
        assertTrue(gate.wantConnected(kst("2026-10-28T15:45:09")));
        assertFalse(gate.wantConnected(kst("2026-10-28T15:45:10")));
    }

    @Test
    void stillConnectsTheLeadBeforeAnOpen() {
        assertFalse(gate.wantConnected(kst("2026-10-28T08:43:59")));
        assertTrue(gate.wantConnected(kst("2026-10-28T08:44:00")));
    }

    @Test
    void noLingerKeepsTheOldBehaviour() {
        SessionGate none = new SessionGate(TestCalendars.fixed(), true, LEAD, Duration.ZERO);
        assertTrue(none.wantConnected(kst("2026-10-28T05:59:59")));
        assertFalse(none.wantConnected(kst("2026-10-28T06:00:00")));
    }

    @Test
    void lingerDoesNotMakeAnythingOpenOnAHolidayOrWeekend() {
        // 금요일 야간은 토요일 06:00 에 끝난다. 토요일 저녁·일요일은 계속 닫혀 있다.
        assertTrue(gate.wantConnected(kst("2026-10-31T06:00:05")));
        assertFalse(gate.wantConnected(kst("2026-10-31T06:00:10")));
        assertFalse(gate.wantConnected(kst("2026-11-01T06:00:05")));
    }

    @Test
    void aDisabledGateIsAlwaysConnected() {
        SessionGate off = new SessionGate(TestCalendars.fixed(), false, LEAD, LINGER);
        assertTrue(off.wantConnected(kst("2026-10-31T12:00:00")));
    }
}
