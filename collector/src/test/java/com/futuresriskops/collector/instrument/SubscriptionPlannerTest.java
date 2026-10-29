package com.futuresriskops.collector.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TestCalendars;
import com.futuresriskops.collector.session.TradingSession;
import com.futuresriskops.collector.ws.Subscription;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubscriptionPlannerTest {

    private static final SessionCalendar CAL = TestCalendars.fixed();

    private static Instant kst(String t) {
        return ZonedDateTime.parse(t + "+09:00[Asia/Seoul]").toInstant();
    }

    private static List<String> subs(SubscriptionPlanner p, String at) {
        return p.plan(kst(at), FuturesContracts.empty()).orElseThrow().subscriptions().stream()
                .map(Subscription::toString).toList();
    }

    @Test
    void daySessionSubscribesTradesAndQuotesNightSessionOnlyNightTrades() {
        SubscriptionPlanner p = new SubscriptionPlanner(CAL, SubscriptionPlanner.Mode.AUTO, List.of(), true);
        assertEquals(List.of("H0IFCNT0:A01612", "H0IFASP0:A01612"), subs(p, "2026-10-29T10:00:00"));
        assertEquals(List.of("H0MFCNT0:A01612"), subs(p, "2026-10-29T20:00:00"));
        assertEquals(List.of("H0MFCNT0:A01612"), subs(p, "2026-10-30T02:00:00"));
    }

    @Test
    void outsideASessionItPlansTheNextOne() {
        SubscriptionPlanner p = new SubscriptionPlanner(CAL, SubscriptionPlanner.Mode.AUTO, List.of(), true);
        SubscriptionPlanner.Result r = p.plan(kst("2026-10-29T17:59:00"), FuturesContracts.empty()).orElseThrow();
        assertEquals(TradingSession.NIGHT, r.window().session());
        assertEquals(List.of("H0MFCNT0:A01612"), subs(p, "2026-10-29T17:59:00"));
        assertEquals(List.of("H0IFCNT0:A01612", "H0IFASP0:A01612"), subs(p, "2026-10-31T12:00:00")); // 토요일 → 월요일 주간
    }

    @Test
    void rolloverOverlapUsesBothCodesForEveryTr() {
        SubscriptionPlanner p = new SubscriptionPlanner(CAL, SubscriptionPlanner.Mode.AUTO, List.of(), false);
        assertEquals(List.of("H0IFCNT0:A01612", "H0IFCNT0:A01703"), subs(p, "2026-12-10T09:00:00"));
        assertEquals(List.of("H0MFCNT0:A01703"), subs(p, "2026-12-10T15:46:00"));
    }

    @Test
    void fixedModeUsesTheConfiguredSymbols() {
        SubscriptionPlanner p = new SubscriptionPlanner(CAL, SubscriptionPlanner.Mode.FIXED, List.of("A01612"), false);
        assertEquals(List.of("H0IFCNT0:A01612"), subs(p, "2026-12-11T09:00:00"));
        assertEquals(List.of("H0MFCNT0:A01612"), subs(p, "2026-12-11T19:00:00"));
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionPlanner(CAL, SubscriptionPlanner.Mode.FIXED, List.of(), false));
    }
}
