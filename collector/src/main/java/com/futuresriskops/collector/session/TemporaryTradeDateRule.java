package com.futuresriskops.collector.session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 체결 시각(HHMMSS)과 수신 시각으로 체결 일시·거래일·세션을 정하는 <b>임시 규칙</b>.
 * Day 2 세션 캘린더가 이 계산을 넘겨받는다.
 *
 * <ul>
 *   <li>체결 일시: 수신 시각의 한국 날짜 + 체결 시각. 체결 시각이 수신 시각보다 12시간 넘게 뒤면 전날로 본다
 *       (23:59:59 체결을 00:00:01 에 받은 경우). 반대로 12시간 넘게 앞서면 다음 날로 본다.</li>
 *   <li>거래일: 체결 일시의 한국 날짜. 실제 KRX 규칙(야간장 거래일)은 반영하지 않았다.</li>
 *   <li>세션: 체결 시각이 18:00 이후이거나 06:00 전이면 NIGHT, 아니면 DAY.</li>
 * </ul>
 */
public final class TemporaryTradeDateRule {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_START = LocalTime.of(18, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);

    private TemporaryTradeDateRule() {
    }

    public record Placement(ZonedDateTime tradedAt, LocalDate tradeDate, TradingSession session) {
    }

    public static Placement place(LocalTime bsopHour, Instant receivedAt) {
        LocalDateTime received = LocalDateTime.ofInstant(receivedAt, KST);
        LocalDateTime traded = LocalDateTime.of(received.toLocalDate(), bsopHour);
        if (traded.isAfter(received.plusHours(12))) {
            traded = traded.minusDays(1);
        } else if (traded.isBefore(received.minusHours(12))) {
            traded = traded.plusDays(1);
        }
        TradingSession session = !bsopHour.isBefore(NIGHT_START) || bsopHour.isBefore(NIGHT_END)
                ? TradingSession.NIGHT : TradingSession.DAY;
        return new Placement(traded.atZone(KST), traded.toLocalDate(), session);
    }
}
