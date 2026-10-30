package com.futuresriskops.simulator.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * 시뮬레이터 설정. 우선순위는 낮은 쪽부터
 * <ol>
 *   <li>내장 기본값</li>
 *   <li>설정 파일({@code --config=경로}, java properties 형식)</li>
 *   <li>환경변수 {@code SIM_*} (예: {@code rate.ticks-per-second} → {@code SIM_RATE_TICKS_PER_SECOND})</li>
 *   <li>명령행 {@code --키=값}</li>
 * </ol>
 * 알 수 없는 키(설정 파일·명령행)와 범위를 벗어난 값은 {@link IllegalArgumentException} 으로 거절한다.
 */
public record SimulatorConfig(
        Server server,
        Price price,
        Rate rate,
        Delay delay,
        Disconnect disconnect,
        Stall stall,
        PingPong pingpong,
        Resend resend,
        ClockStart clock) {

    /** @param port 0 이면 임의의 빈 포트 */
    public record Server(String host, int port, int maxSubscriptions) {
    }

    /**
     * @param base       시작 가격(지수 포인트)
     * @param prevClose  전일 종가. 상·하한가(±10%)와 전일 대비 계산의 기준
     * @param tickSize   호가 단위
     * @param volatilityTicks 체결마다 움직이는 폭의 표준편차(틱 단위)
     * @param multiplier 거래승수(원/포인트). 누적 거래대금 계산에 쓴다
     * @param maxQty     한 번 체결 수량의 상한
     * @param seed       주어지면 같은 가격 경로를 재현한다
     */
    public record Price(double base, double prevClose, double tickSize, double volatilityTicks,
                        long multiplier, int maxQty, OptionalLong seed) {
    }

    /** @param jitter 체결 간격을 평균의 ±jitter 비율만큼 흔든다(0~1) */
    public record Rate(double ticksPerSecond, double jitter) {
    }

    /**
     * 연결별 전달 지연. 체결 시각(bsop_hour)은 그대로 두고 전송만 늦춘다.
     * 지연은 {@code fixedMs + [0, jitterMs)} 이고, {@code spikeProbability} 확률로 {@code spikeMs} 가 더해진다.
     * 전달 순서는 지켜진다.
     */
    public record Delay(double fixedMs, double jitterMs, double spikeProbability, double spikeMs) {
    }

    /**
     * 연결 끊김.
     *
     * @param afterSeconds         연결 후 이 시간이 지나면 서버가 끊는다(0 이면 끔)
     * @param probabilityPerTick   체결을 보낼 때마다 이 확률로 끊는다(0~1)
     * @param abrupt               true 면 close 프레임 없이 TCP 를 리셋한다(비정상 종료), false 면 close 1001
     */
    public record Disconnect(double afterSeconds, double probabilityPerTick, boolean abrupt) {
    }

    /**
     * 조용한 중단. 연결은 유지한 채 체결 전송만 멈춘다(멈춘 동안의 체결은 버린다).
     *
     * @param afterSeconds    연결 후 중단이 시작되기까지의 시간
     * @param durationSeconds 중단 길이(0 이면 기능 꺼짐)
     * @param repeat          true 면 (정상 afterSeconds → 중단 durationSeconds) 를 반복
     */
    public record Stall(double afterSeconds, double durationSeconds, boolean repeat) {
        public boolean enabled() {
            return durationSeconds > 0;
        }

        /** 연결 후 {@code elapsedSeconds} 가 지난 시점에 중단 구간인지. */
        public boolean isStalledAt(double elapsedSeconds) {
            if (!enabled() || elapsedSeconds < afterSeconds) {
                return false;
            }
            if (!repeat) {
                return elapsedSeconds < afterSeconds + durationSeconds;
            }
            double period = afterSeconds + durationSeconds;
            return (elapsedSeconds % period) >= afterSeconds;
        }
    }

    /** @param intervalSeconds 서버가 PINGPONG 메시지를 보내는 간격(0 이면 보내지 않음) */
    public record PingPong(double intervalSeconds) {
    }

    /**
     * 구독할 때 그 종목의 최근 체결을 다시 보낸다. 재접속한 클라이언트가 이미 받은 체결을 또 받는 상황을 만든다
     * (중복 적재 방지 확인용). 실제 KIS 가 이렇게 하는지는 확인하지 않았다.
     *
     * @param onSubscribe 다시 보낼 최근 체결 수(0 이면 끔)
     */
    public record Resend(int onSubscribe) {
    }

    /**
     * 시뮬레이터 시계의 시작 시각(한국 시간). 비어 있으면 실제 시각이다. 장 시간이 아닐 때도 체결 시각(bsop_hour)과
     * PINGPONG 시각을 장 시간처럼 만들어 세션 캘린더·근월물 전환을 확인한다. 수집기도 같은 시각(collector.clock-start)으로 맞춘다.
     */
    public record ClockStart(Optional<LocalDateTime> start) {
    }

    // ------------------------------------------------------------------ 옵션 정의

    /** 옵션 하나의 키·기본값·설명. {@code --help} 와 문서가 이 목록을 그대로 쓴다. */
    public record Option(String key, String defaultValue, String description) {
    }

    public static final List<Option> OPTIONS = List.of(
            new Option("server.host", "127.0.0.1", "bind address (127.0.0.1 = this PC only)"),
            new Option("server.port", "18080", "listen port (0 = any free port)"),
            new Option("server.max-subscriptions", "40", "max subscriptions per connection (KIS sample limit is 40)"),
            new Option("price.base", "400.00", "starting price in index points"),
            new Option("price.prev-close", "", "previous close; empty = price.base"),
            new Option("price.tick-size", "0.05", "price tick"),
            new Option("price.volatility-ticks", "1.5", "std-dev of each price step, in ticks"),
            new Option("price.multiplier", "250000", "KRW per index point (for cumulative traded value)"),
            new Option("price.max-qty", "10", "max quantity of one trade"),
            new Option("price.seed", "", "random seed for a reproducible price path; empty = random"),
            new Option("rate.ticks-per-second", "5", "trades per second per symbol (0.01 - 5000)"),
            new Option("rate.jitter", "0.2", "random variation of the interval, 0 - 1"),
            new Option("delay.fixed-ms", "0", "constant delivery delay in ms"),
            new Option("delay.jitter-ms", "0", "extra random delay in [0, value) ms"),
            new Option("delay.spike-probability", "0", "chance (0 - 1) per trade of an extra delay spike"),
            new Option("delay.spike-ms", "0", "size of a delay spike in ms"),
            new Option("disconnect.after-seconds", "0", "server closes the connection this long after connect; 0 = off"),
            new Option("disconnect.probability-per-tick", "0", "chance (0 - 1) per trade of closing the connection"),
            new Option("disconnect.abrupt", "false", "true = reset TCP without a close frame; false = close code 1001"),
            new Option("stall.after-seconds", "0", "seconds after connect before sending silently stops"),
            new Option("stall.duration-seconds", "0", "how long sending stays stopped; 0 = stall off"),
            new Option("stall.repeat", "false", "repeat run-then-stall cycles"),
            new Option("pingpong.interval-seconds", "30", "interval of PINGPONG messages; 0 = never"),
            new Option("resend.on-subscribe", "0", "on subscribe, send again the last N trades of that symbol; 0 = off"),
            new Option("clock.start", "", "KST wall clock to start from, e.g. 2026-12-10T08:44:00; empty = real time"));

    // ------------------------------------------------------------------ 로딩

    /** 명령행 인자와 환경변수로 설정을 만든다. {@code --config} 파일이 있으면 읽는다. */
    public static SimulatorConfig load(String[] args, Map<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Option o : OPTIONS) {
            values.put(o.key(), o.defaultValue());
        }
        Map<String, String> cli = parseArgs(args);
        String configPath = cli.remove("config");
        if (configPath != null) {
            applyAll(values, readProperties(Path.of(configPath)), "config file");
        }
        for (Option o : OPTIONS) {
            String v = env.get(envName(o.key()));
            if (v != null) {
                values.put(o.key(), v.trim());
            }
        }
        applyAll(values, cli, "command line");
        return fromMap(values);
    }

    /** 키 → 값 맵(전체 키가 있어야 함)에서 설정을 만든다. 테스트에서 직접 쓴다. */
    public static SimulatorConfig fromMap(Map<String, String> v) {
        double base = dbl(v, "price.base", 0.0001, 1_000_000);
        String prev = v.get("price.prev-close");
        double prevClose = prev == null || prev.isBlank() ? base : dbl(v, "price.prev-close", 0.0001, 1_000_000);
        double tick = dbl(v, "price.tick-size", 0.0001, 1000);
        String seedText = v.get("price.seed");
        OptionalLong seed = seedText == null || seedText.isBlank()
                ? OptionalLong.empty() : OptionalLong.of(lng(v, "price.seed", Long.MIN_VALUE, Long.MAX_VALUE));

        Server server = new Server(
                str(v, "server.host"),
                (int) lng(v, "server.port", 0, 65535),
                (int) lng(v, "server.max-subscriptions", 1, 1000));
        Price price = new Price(base, prevClose, tick,
                dbl(v, "price.volatility-ticks", 0, 1000),
                lng(v, "price.multiplier", 1, 1_000_000_000L),
                (int) lng(v, "price.max-qty", 1, 1_000_000),
                seed);
        Rate rate = new Rate(dbl(v, "rate.ticks-per-second", 0.01, 5000), dbl(v, "rate.jitter", 0, 1));
        Delay delay = new Delay(
                dbl(v, "delay.fixed-ms", 0, 3_600_000),
                dbl(v, "delay.jitter-ms", 0, 3_600_000),
                dbl(v, "delay.spike-probability", 0, 1),
                dbl(v, "delay.spike-ms", 0, 3_600_000));
        Disconnect disconnect = new Disconnect(
                dbl(v, "disconnect.after-seconds", 0, 86_400),
                dbl(v, "disconnect.probability-per-tick", 0, 1),
                bool(v, "disconnect.abrupt"));
        Stall stall = new Stall(
                dbl(v, "stall.after-seconds", 0, 86_400),
                dbl(v, "stall.duration-seconds", 0, 86_400),
                bool(v, "stall.repeat"));
        PingPong ping = new PingPong(dbl(v, "pingpong.interval-seconds", 0, 86_400));
        Resend resend = new Resend((int) lng(v, "resend.on-subscribe", 0, 10_000));
        ClockStart clock = new ClockStart(dateTime(v, "clock.start"));
        return new SimulatorConfig(server, price, rate, delay, disconnect, stall, ping, resend, clock);
    }

    /** 사람이 읽는 유효 설정 요약(시작할 때 출력). */
    public String describe() {
        List<String> lines = new ArrayList<>();
        lines.add("listen        " + server.host() + ":" + server.port());
        lines.add("price         base=" + price.base() + " prevClose=" + price.prevClose()
                + " tick=" + price.tickSize() + " volatilityTicks=" + price.volatilityTicks()
                + " seed=" + (price.seed().isPresent() ? price.seed().getAsLong() : "random"));
        lines.add("rate          " + rate.ticksPerSecond() + " trades/s per symbol, jitter=" + rate.jitter());
        lines.add("delay         fixed=" + delay.fixedMs() + "ms jitter=" + delay.jitterMs()
                + "ms spike=" + delay.spikeProbability() + "x" + delay.spikeMs() + "ms");
        lines.add("disconnect    after=" + disconnect.afterSeconds() + "s probabilityPerTick="
                + disconnect.probabilityPerTick() + " abrupt=" + disconnect.abrupt());
        lines.add("stall         after=" + stall.afterSeconds() + "s duration=" + stall.durationSeconds()
                + "s repeat=" + stall.repeat());
        lines.add("pingpong      every " + pingpong.intervalSeconds() + "s");
        lines.add("resend        last " + resend.onSubscribe() + " trades on subscribe");
        lines.add("clock         " + clock.start().map(t -> "starts at " + t + " KST").orElse("real time"));
        return String.join(System.lineSeparator(), lines);
    }

    /** {@code --help} 출력. */
    public static String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("KIS fake futures WebSocket simulator (H0IFCNT0 / H0MFCNT0 trades, H0IFASP0 quotes)")
                .append(System.lineSeparator());
        sb.append("usage: --key=value ... [--config=simulator.properties]").append(System.lineSeparator());
        sb.append("env:   SIM_<KEY> with '.' and '-' replaced by '_' (e.g. SIM_RATE_TICKS_PER_SECOND=50)")
                .append(System.lineSeparator()).append(System.lineSeparator());
        for (Option o : OPTIONS) {
            sb.append(String.format("  --%-32s %s [default: %s]%n", o.key(), o.description(),
                    o.defaultValue().isEmpty() ? "(empty)" : o.defaultValue()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 내부

    static String envName(String key) {
        return "SIM_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || a.length() == 2) {
                throw new IllegalArgumentException("unexpected argument '" + a + "' (use --key=value)");
            }
            String body = a.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.put(body.substring(0, eq), body.substring(eq + 1).trim());
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(body, args[++i].trim());
            } else {
                out.put(body, "true");
            }
        }
        return out;
    }

    private static Map<String, String> readProperties(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read config file " + file + ": " + e.getMessage());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            out.put(name, p.getProperty(name).trim());
        }
        return out;
    }

    private static void applyAll(Map<String, String> target, Map<String, String> source, String origin) {
        for (Map.Entry<String, String> e : source.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                throw new IllegalArgumentException("unknown option '" + e.getKey() + "' in " + origin
                        + " (run with --help to list options)");
            }
            target.put(e.getKey(), e.getValue());
        }
    }

    private static String str(Map<String, String> v, String key) {
        String s = v.get(key);
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        return s.trim();
    }

    private static double dbl(Map<String, String> v, String key, double min, double max) {
        String s = str(v, key);
        double d;
        try {
            d = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number but was '" + s + "'");
        }
        if (Double.isNaN(d) || d < min || d > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + " but was " + s);
        }
        return d;
    }

    private static long lng(Map<String, String> v, String key, long min, long max) {
        String s = str(v, key);
        long n;
        try {
            n = Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer but was '" + s + "'");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + " but was " + s);
        }
        return n;
    }

    private static Optional<LocalDateTime> dateTime(Map<String, String> v, String key) {
        String s = v.get(key);
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(s.trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " must look like 2026-12-10T08:44:00 but was '" + s + "'");
        }
    }

    private static boolean bool(Map<String, String> v, String key) {
        String s = str(v, key).toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false but was '" + s + "'");
    }
}
