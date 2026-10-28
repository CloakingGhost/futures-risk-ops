package com.futuresriskops.collector.monitor;

import com.futuresriskops.collector.session.SessionCalendar;
import java.time.Duration;
import java.time.Instant;

/**
 * 세션 캘린더로 '지금 붙어 있어야 하나' 를 정한다. 꺼져 있으면(시뮬레이터 기본) 언제나 붙는다.
 * 세션 시작 {@code lead} 전에 붙고, 세션이 끝나면 끊는다.
 */
public final class SessionGate {

    private final SessionCalendar calendar;
    private final boolean enabled;
    private final Duration lead;

    public SessionGate(SessionCalendar calendar, boolean enabled, Duration lead) {
        this.calendar = calendar;
        this.enabled = enabled;
        this.lead = lead;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean wantConnected(Instant now) {
        return !enabled || calendar.shouldBeConnected(now, lead);
    }

    /** 체결이 와야 하는 때인가(공백 감지용). 게이트가 꺼져 있으면 언제나 그렇다고 본다. */
    public boolean inSession(Instant now) {
        return !enabled || calendar.isOpen(now);
    }

    /** 다음 세션 시작 시각 안내(로그용). */
    public String nextOpenText(Instant now) {
        return calendar.nextOpen(now).map(w -> w.session() + " " + w.open()).orElse("none within a month");
    }
}
