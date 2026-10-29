package com.futuresriskops.collector.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.collector.monitor.GapDetector.Kind;
import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 값은 손으로 만든 합성 체결이다. */
class GapDetectorTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 28);
    private final GapDetector g = new GapDetector();

    private Kind day(int qty, long acc) {
        return g.observe("A01612", D, TradingSession.DAY, qty, acc).kind();
    }

    @Test
    void consecutiveTradesAreOk() {
        assertEquals(Kind.FIRST, day(3, 103)); // 켜기 전 거래량 100 은 누락으로 세지 않는다
        assertEquals(Kind.OK, day(2, 105));
        assertEquals(Kind.OK, day(1, 106));
        assertEquals(0, g.missingVolume());
        assertEquals(3, g.expectedVolume());
        assertEquals(3, g.receivedVolume());
        assertEquals(0.0, g.missingRate());
    }

    @Test
    void jumpBiggerThanQtyIsAGap() {
        day(1, 10);
        GapDetector.Observation o = g.observe("A01612", D, TradingSession.DAY, 2, 20);
        assertEquals(Kind.GAP, o.kind());
        assertEquals(8, o.missing());
        assertEquals(1, g.gapCount());
        assertEquals(8, g.missingVolume());
        assertEquals(10, g.expectedVolume());
        assertEquals(0.8, g.missingRate(), 1e-9);
    }

    @Test
    void alreadySeenVolumeIsARepeatAndDoesNotMoveTheBaseline() {
        day(1, 10);
        day(1, 11);
        assertEquals(Kind.REPEAT, day(1, 11));
        assertEquals(Kind.REPEAT, day(1, 10));
        assertEquals(Kind.OK, day(1, 12));
        assertEquals(2, g.repeatCount());
        assertEquals(0, g.missingVolume());
    }

    @Test
    void jumpSmallerThanQtyIsInconsistent() {
        day(5, 50);
        assertEquals(Kind.INCONSISTENT, day(5, 52));
        assertEquals(1, g.inconsistentCount());
    }

    @Test
    void newSessionStartsANewBaseline() {
        day(1, 5_000);
        // 야간장에서 누적거래량이 0부터 다시 시작한다는 가정. 줄어들어도 REPEAT 가 아니다
        assertEquals(Kind.FIRST, g.observe("A01612", D.plusDays(1), TradingSession.NIGHT, 2, 2).kind());
        assertEquals(Kind.OK, g.observe("A01612", D.plusDays(1), TradingSession.NIGHT, 3, 5).kind());
        assertEquals(0, g.repeatCount());
    }

    @Test
    void symbolsAreTrackedSeparately() {
        day(1, 10);
        assertEquals(Kind.FIRST, g.observe("A01703", D, TradingSession.DAY, 1, 3).kind());
        assertEquals(Kind.OK, day(1, 11));
        assertEquals(Kind.OK, g.observe("A01703", D, TradingSession.DAY, 1, 4).kind());
    }

    @Test
    void gapRightAfterANewConnectionIsAReconnectGap() {
        g.onNewConnection();
        day(1, 10);
        assertEquals(Kind.OK, day(1, 11));
        g.onNewConnection(); // 끊겼다 다시 연결
        GapDetector.Observation o = g.observe("A01612", D, TradingSession.DAY, 1, 20);
        assertEquals(Kind.GAP, o.kind());
        assertTrue(o.afterReconnect());
        assertEquals(1, g.reconnectGapCount());
        assertEquals(8, g.reconnectMissingVolume());
        assertEquals(0, g.streamGapCount());
    }

    @Test
    void gapWhileConnectedIsAStreamGap() {
        g.onNewConnection();
        day(1, 10);
        day(1, 11);
        GapDetector.Observation o = g.observe("A01612", D, TradingSession.DAY, 1, 14); // 묶음 전송처럼
        assertEquals(Kind.GAP, o.kind());
        assertFalse(o.afterReconnect());
        assertEquals(1, g.streamGapCount());
        assertEquals(2, g.streamMissingVolume());
        assertEquals(0, g.reconnectGapCount());
        assertEquals(0.0, g.reconnectMissingRate());
    }

    @Test
    void resentTradesDoNotUseUpTheReconnectMark() {
        g.onNewConnection();
        day(1, 10);
        day(1, 11);
        g.onNewConnection();
        assertEquals(Kind.REPEAT, day(1, 10)); // 재전송
        assertEquals(Kind.REPEAT, day(1, 11));
        GapDetector.Observation o = g.observe("A01612", D, TradingSession.DAY, 1, 15);
        assertTrue(o.afterReconnect(), "the first new trade after the resend still counts as after reconnect");
        assertEquals(1, g.reconnectGapCount());
    }
}
