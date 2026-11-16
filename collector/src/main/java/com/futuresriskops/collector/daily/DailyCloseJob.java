package com.futuresriskops.collector.daily;

import com.futuresriskops.collector.instrument.FuturesContract;
import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.instrument.FuturesExpiry;
import com.futuresriskops.collector.instrument.NearMonthPlanner;
import com.futuresriskops.collector.rest.DailyBar;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import com.futuresriskops.collector.store.TickTrade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 거래일마다 KIS 일봉을 받아 daily_bar 에 upsert 한다. 일봉은 계산하지 않고 KIS 가 준 값을 그대로 둔다.
 *
 * <p>무엇을 받나: 마지막 확정 영업일({@code to})까지 최근 {@code lookbackDays} 영업일을, 그날 주간 세션의 구독 종목
 * ({@link NearMonthPlanner}: 근월물, 만기 전날부터는 다음 결제월물도)마다 설정된 시장 코드(기본 F)로 요청한다.
 * 며칠 빠져도 다음 실행이 메우고, 같은 구간을 두 번 받아도 결과가 같다.
 *
 * <p>{@code to} 는 오늘 실행 시각({@link #runTimeOn(LocalDate)})이 지났으면 오늘, 아니면(장중이거나 실행 시각 전, 휴장일·주말)
 * 직전 거래일이다. 진행 중인 오늘 값(응답의 현재가 필드)을 확정 일봉처럼 저장하지 않으려는 것이다(가정: 장중에 부르면 진행 중인 값이 온다).
 * 정해진 시각의 스케줄은 {@link #runScheduled()} 로 오늘을 포함한다. 휴장일·주말, 주간장이 닫힌 날에는 돌지 않는다(세션 캘린더가 정한다).
 * 실행 시각은 {@link #nextRunAfter(Instant)}.
 *
 * <p>성공으로 치는 것: 요청이 하나 이상 있었고 모두 성공한 실행. 한 요청이라도 실패하면 {@link #lastSuccess()} 는
 * 갱신하지 않는다(지표가 멈춰 알람이 되도록). 실패해도 나머지 요청은 계속한다.
 */
public final class DailyCloseJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCloseJob.class);

    /**
     * @param ran       오늘 받는 날이어서 요청을 보냈는지(false 면 휴장일 등으로 건너뜀)
     * @param requests  보낸 요청 수(종목 × 시장)
     * @param failures  실패한 요청 수
     * @param rows      daily_bar 에 쓴 행 수
     */
    public record Result(boolean ran, int requests, int failures, int rows) {
        static Result skipped() {
            return new Result(false, 0, 0, 0);
        }

        public boolean succeeded() {
            return ran && requests > 0 && failures == 0;
        }
    }

    private final DailyBarSource source;
    private final DailyBarRepository repository;
    private final SessionCalendar calendar;
    private final Supplier<FuturesContracts> contracts;
    private final TickTrade.Source dataSource;
    private final Clock clock;
    private final DailyCloseProperties props;
    private volatile Instant lastSuccess;

    /**
     * @param contracts  지금 알고 있는 선물 종목(마스터). 없으면 결제월 규칙으로 만든 코드를 쓴다
     * @param dataSource daily_bar.source 에 적을 출처(KIS 실전, KIS_PAPER 모의투자)
     */
    public DailyCloseJob(DailyBarSource source, DailyBarRepository repository, SessionCalendar calendar,
                         Supplier<FuturesContracts> contracts, TickTrade.Source dataSource, Clock clock,
                         DailyCloseProperties props) {
        this.source = source;
        this.repository = repository;
        this.calendar = calendar;
        this.contracts = contracts;
        this.dataSource = dataSource;
        this.clock = clock;
        this.props = props;
    }

    /** 마지막으로 성공한 시각. 아직 없으면 null. */
    public Instant lastSuccess() {
        return lastSuccess;
    }

    /**
     * 시작 직후나 수동 실행용. 오늘 실행 시각 전(장중 포함)이거나 쉬는 날이면 직전 거래일까지만 받는다.
     */
    public Result runOnce() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, calendar.zone());
        boolean todayFinal = runTimeOn(today).map(t -> !now.isBefore(t)).orElse(false);
        LocalDate to = todayFinal ? today : FuturesExpiry.previousTradingDay(today, calendar);
        return run(now, to);
    }

    /** 스케줄이 실행 시각에 부른다. 오늘 주간장이 열린 날이 아니면 건너뛰고, 열렸으면 오늘까지 받는다. */
    public Result runScheduled() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, calendar.zone());
        if (calendar.hours(today, TradingSession.DAY).isEmpty()) {
            LOG.info("daily close skipped: {} has no day session", today);
            return Result.skipped();
        }
        return run(now, today);
    }

    private Result run(Instant now, LocalDate to) {
        LocalDate from = to;
        for (int i = 1; i < props.lookbackDays(); i++) {
            from = FuturesExpiry.previousTradingDay(from, calendar);
        }
        List<FuturesContract> plan = new NearMonthPlanner(calendar).plan(to, TradingSession.DAY, contracts.get())
                .contracts();
        int requests = 0;
        int failures = 0;
        int rows = 0;
        for (FuturesContract c : plan) {
            for (String market : props.markets()) {
                requests++;
                try {
                    List<DailyBar> bars = source.fetch(market, c.symbol(), from, to);
                    rows += repository.upsert(c.symbol(), market, dataSource, bars, now);
                } catch (RuntimeException e) {
                    failures++;
                    // 메시지에는 키·토큰 값이 없다(클라이언트가 HTTP 코드와 필드 이름만 쓴다)
                    LOG.warn("daily close failed for {} {} {}..{}: {}", market, c.symbol(), from, to, e.getMessage());
                }
            }
        }
        Result result = new Result(true, requests, failures, rows);
        if (result.succeeded()) {
            lastSuccess = now;
        }
        LOG.info("daily close {}..{}: requests={}, failures={}, rows={}", from, to, requests, failures, rows);
        return result;
    }

    /**
     * {@code after} 보다 뒤인 가장 가까운 실행 시각. 주간장이 열리는 날의 {@code max(runAt, 주간장 마감 + afterClose)} 다.
     * 평소(마감 15:45)에는 16:00 이고, 마감이 늦어진 날(예: 수능일 16:45)은 마감 15분 뒤로 미룬다.
     */
    public Instant nextRunAfter(Instant after) {
        LocalDate d = LocalDate.ofInstant(after, calendar.zone());
        for (int i = 0; i < 14; i++, d = d.plusDays(1)) {
            Optional<Instant> t = runTimeOn(d);
            if (t.isPresent() && t.get().isAfter(after)) {
                return t.get();
            }
        }
        throw new IllegalStateException("no day session within 14 days after " + after);
    }

    /** 그날 일봉을 받는 시각. 주간장이 열리지 않는 날은 빈 값. */
    public Optional<Instant> runTimeOn(LocalDate d) {
        return calendar.hours(d, TradingSession.DAY).map(h -> {
            LocalTime afterClose = h.close().plus(props.afterClose());
            LocalTime at = afterClose.isAfter(props.runAt()) ? afterClose : props.runAt();
            return d.atTime(at).atZone(calendar.zone()).toInstant();
        });
    }
}
