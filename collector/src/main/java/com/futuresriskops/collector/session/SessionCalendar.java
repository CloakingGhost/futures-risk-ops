package com.futuresriskops.collector.session;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 장 시간 캘린더(순수 함수만). 값은 {@code config/session-calendar.yaml} 에서 {@link SessionCalendarYaml} 이 읽는다.
 * Java 코드에는 장 시간을 적지 않는다.
 *
 * <ul>
 *   <li>거래일: 토·일과 휴장일이 아닌 날.</li>
 *   <li>주간장: 거래일의 day 시간. 야간장: 거래일 저녁 night.open 에 열려 다음 날 night.close 에 닫힌다
 *       (금요일 야간장은 토요일 06:00 종료). 야간장이 거래일 저녁에만 열린다는 것은 가정이다.</li>
 *   <li>특수 개장(overrides): 그날의 day·night 시간을 덮어쓰거나 닫는다.</li>
 *   <li>세션 구간은 열린 시각 포함, 닫힌 시각 제외다.</li>
 * </ul>
 */
public final class SessionCalendar {

    /** 야간장 체결의 거래일. 어느 쪽이 맞는지는 KRX 규정으로 확인하기 전까지 가정이다. */
    public enum NightTradeDate {
        /** 야간장이 열린 날의 다음 거래일. 금요일 야간장 → 다음 주 월요일 */
        NEXT_TRADING_DAY,
        /** 야간장이 열린 날 */
        OPEN_DATE
    }

    /** 세션 하나의 시각. close 가 open 보다 이르거나 같으면 다음 날 닫힌다. */
    public record Hours(LocalTime open, LocalTime close) {
        boolean crossesMidnight() {
            return !close.isAfter(open);
        }
    }

    /**
     * 하루의 덮어쓰기. 맵에 없는 세션은 평소대로, 값이 비어 있으면(Optional.empty) 그 세션이 없다.
     */
    public record Override(LocalDate date, String reason, Map<TradingSession, Optional<Hours>> sessions) {
        public Override {
            sessions = Map.copyOf(sessions);
        }
    }

    /** 실제로 열리는 세션 구간 하나. */
    public record Window(TradingSession session, LocalDate openDate, LocalDate tradeDate, ZonedDateTime open,
                         ZonedDateTime close) {
        public boolean contains(Instant t) {
            return !t.isBefore(open.toInstant()) && t.isBefore(close.toInstant());
        }
    }

    /** 체결 한 건의 체결 일시·거래일·세션. */
    public record Placement(ZonedDateTime tradedAt, LocalDate tradeDate, TradingSession session) {
    }

    /** 다음 거래일·다음 세션을 찾을 때 넘지 않는 범위(긴 연휴도 이 안에 끝난다). */
    private static final int SEARCH_DAYS = 31;

    private final ZoneId zone;
    private final Hours day;
    private final Hours night;
    private final NightTradeDate nightTradeDate;
    private final Map<LocalDate, String> holidays;
    private final Map<LocalDate, Override> overrides;

    public SessionCalendar(ZoneId zone, Hours day, Hours night, NightTradeDate nightTradeDate,
                           Map<LocalDate, String> holidays, List<Override> overrides) {
        if (day.crossesMidnight()) {
            throw new IllegalArgumentException("day session must close after it opens: " + day);
        }
        if (!night.crossesMidnight()) {
            throw new IllegalArgumentException("night session must close on the next day: " + night);
        }
        this.zone = zone;
        this.day = day;
        this.night = night;
        this.nightTradeDate = nightTradeDate;
        this.holidays = Map.copyOf(holidays);
        Map<LocalDate, Override> byDate = new HashMap<>();
        for (Override o : overrides) {
            if (byDate.put(o.date(), o) != null) {
                throw new IllegalArgumentException("two overrides for " + o.date());
            }
        }
        this.overrides = Map.copyOf(byDate);
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean isTradingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.containsKey(d);
    }

    public LocalDate nextTradingDay(LocalDate d) {
        LocalDate x = d.plusDays(1);
        for (int i = 0; i < SEARCH_DAYS; i++, x = x.plusDays(1)) {
            if (isTradingDay(x)) {
                return x;
            }
        }
        throw new IllegalStateException("no trading day within " + SEARCH_DAYS + " days after " + d);
    }

    /** 그날 열리는 세션 시각. 거래일이 아니거나 덮어쓰기로 닫혔으면 빈 값. */
    public Optional<Hours> hours(LocalDate openDate, TradingSession session) {
        if (!isTradingDay(openDate)) {
            return Optional.empty();
        }
        Override o = overrides.get(openDate);
        if (o != null && o.sessions().containsKey(session)) {
            return o.sessions().get(session);
        }
        return Optional.of(session == TradingSession.DAY ? day : night);
    }

    /** 그날(openDate) 열리는 세션 구간들, 여는 순서대로. */
    public List<Window> windows(LocalDate openDate) {
        List<Window> out = new ArrayList<>(2);
        for (TradingSession s : TradingSession.values()) {
            hours(openDate, s).ifPresent(h -> {
                ZonedDateTime open = openDate.atTime(h.open()).atZone(zone);
                LocalDate closeDate = h.crossesMidnight() ? openDate.plusDays(1) : openDate;
                ZonedDateTime close = closeDate.atTime(h.close()).atZone(zone);
                out.add(new Window(s, openDate, tradeDate(s, openDate), open, close));
            });
        }
        out.sort(Comparator.comparing(Window::open));
        return out;
    }

    /** t 에 열려 있는 세션. */
    public Optional<Window> sessionAt(Instant t) {
        LocalDate d = LocalDate.ofInstant(t, zone);
        for (LocalDate openDate : List.of(d.minusDays(1), d)) {
            for (Window w : windows(openDate)) {
                if (w.contains(t)) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    /** 지금 세션 안인가. Day 3 의 collector_market_session_open 지표가 이 함수를 그대로 쓴다. */
    public boolean isOpen(Instant t) {
        return sessionAt(t).isPresent();
    }

    /** t 이후(t 는 빼고)에 처음 열리는 세션. */
    public Optional<Window> nextOpen(Instant t) {
        LocalDate d = LocalDate.ofInstant(t, zone);
        for (int i = 0; i <= SEARCH_DAYS; i++) {
            for (Window w : windows(d.plusDays(i))) {
                if (w.open().toInstant().isAfter(t)) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    /** 수집기가 연결해 있어야 하는가: 세션 안이거나, lead 안에 세션이 열린다(시작 1분 전 접속). */
    public boolean shouldBeConnected(Instant now, Duration lead) {
        return isOpen(now) || isOpen(now.plus(lead));
    }

    /**
     * 체결 시각(KST)의 거래일과 세션. 시각만으로 정하므로 장 시간 밖 체결(시뮬레이터)에도 값이 나온다.
     * 세션은 평소 야간장 시각으로 나눈다(덮어쓰기는 반영하지 않는다).
     */
    public Placement classify(ZonedDateTime tradedAt) {
        ZonedDateTime t = tradedAt.withZoneSameInstant(zone);
        LocalTime time = t.toLocalTime();
        LocalDate date = t.toLocalDate();
        if (!time.isBefore(night.open())) {
            return new Placement(t, tradeDate(TradingSession.NIGHT, date), TradingSession.NIGHT);
        }
        if (time.isBefore(night.close())) {
            return new Placement(t, tradeDate(TradingSession.NIGHT, date.minusDays(1)), TradingSession.NIGHT);
        }
        return new Placement(t, date, TradingSession.DAY);
    }

    /**
     * 체결 시각(HHMMSS, 날짜 없음)과 수신 시각으로 체결 일시를 정하고 {@link #classify} 한다.
     * 날짜는 수신 시각의 날짜로 두되, 체결 시각이 수신 시각보다 12시간 넘게 뒤면 전날(23:59:59 체결을 00:00:01 에 받은 경우),
     * 12시간 넘게 앞서면 다음 날로 본다.
     */
    public Placement place(LocalTime bsopHour, Instant receivedAt) {
        LocalDateTime received = LocalDateTime.ofInstant(receivedAt, zone);
        LocalDateTime traded = LocalDateTime.of(received.toLocalDate(), bsopHour);
        if (traded.isAfter(received.plusHours(12))) {
            traded = traded.minusDays(1);
        } else if (traded.isBefore(received.minusHours(12))) {
            traded = traded.plusDays(1);
        }
        return classify(traded.atZone(zone));
    }

    private LocalDate tradeDate(TradingSession session, LocalDate openDate) {
        if (session == TradingSession.DAY || nightTradeDate == NightTradeDate.OPEN_DATE) {
            return openDate;
        }
        return nextTradingDay(openDate);
    }

    /** 사람이 읽는 요약(시작 로그). */
    public String describe() {
        return "day " + day.open() + "-" + day.close() + ", night " + night.open() + "-" + night.close()
                + " (+1d), night trade date " + nightTradeDate + ", " + holidays.size() + " holidays, "
                + overrides.size() + " overrides";
    }
}
