package com.futuresriskops.collector.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code config/session-calendar.yaml} 을 읽어 {@link SessionCalendar} 를 만든다.
 * 다른 언어(알람 규칙, 스크립트)도 같은 파일을 읽으므로 형식은 그 파일 주석이 기준이다.
 * 모르는 키, 빠진 값, 잘못된 시각은 시작할 때 실패시킨다(틀린 캘린더로 조용히 도는 것보다 낫다).
 */
public final class SessionCalendarYaml {

    private static final Set<String> TOP_KEYS = Set.of("timezone", "regular", "night_trade_date", "holidays",
            "overrides");

    private SessionCalendarYaml() {
    }

    public static SessionCalendar read(InputStream in) {
        JsonNode root;
        try {
            root = new YAMLMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("session calendar is not valid YAML: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("session calendar must be a YAML mapping");
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!TOP_KEYS.contains(k)) {
                throw new IllegalArgumentException("unknown key '" + k + "' in session calendar");
            }
        }
        ZoneId zone = ZoneId.of(text(root, "timezone"));
        JsonNode regular = required(root, "regular");
        SessionCalendar.Hours day = hours(required(regular, "day"), "regular.day");
        SessionCalendar.Hours night = hours(required(regular, "night"), "regular.night");
        SessionCalendar.NightTradeDate rule;
        try {
            rule = SessionCalendar.NightTradeDate.valueOf(text(root, "night_trade_date").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("night_trade_date must be next_trading_day or open_date");
        }

        Map<LocalDate, String> holidays = new LinkedHashMap<>();
        for (JsonNode h : root.path("holidays")) {
            LocalDate d = date(h, "holidays");
            if (holidays.put(d, h.path("name").asText("")) != null) {
                throw new IllegalArgumentException("holiday " + d + " is listed twice");
            }
        }

        List<SessionCalendar.Override> overrides = new ArrayList<>();
        for (JsonNode o : root.path("overrides")) {
            LocalDate d = date(o, "overrides");
            Map<TradingSession, Optional<SessionCalendar.Hours>> sessions = new EnumMap<>(TradingSession.class);
            for (TradingSession s : TradingSession.values()) {
                JsonNode v = o.get(s.name().toLowerCase(Locale.ROOT));
                if (v == null) {
                    continue;
                }
                if (v.isTextual() && v.asText().equals("closed")) {
                    sessions.put(s, Optional.empty());
                } else {
                    sessions.put(s, Optional.of(hours(v, "overrides " + d + " " + s)));
                }
            }
            if (sessions.isEmpty()) {
                throw new IllegalArgumentException("override " + d + " changes neither day nor night");
            }
            overrides.add(new SessionCalendar.Override(d, o.path("reason").asText(""), sessions));
        }
        return new SessionCalendar(zone, day, night, rule, holidays, overrides);
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("session calendar is missing '" + key + "'");
        }
        return v;
    }

    private static String text(JsonNode node, String key) {
        return required(node, key).asText().trim();
    }

    private static SessionCalendar.Hours hours(JsonNode node, String where) {
        try {
            return new SessionCalendar.Hours(LocalTime.parse(text(node, "open")), LocalTime.parse(text(node, "close")));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": times must be HH:mm (" + e.getParsedString() + ")");
        }
    }

    private static LocalDate date(JsonNode node, String where) {
        String s = text(node, "date");
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": date must be yyyy-MM-dd but was '" + s + "'");
        }
    }
}
