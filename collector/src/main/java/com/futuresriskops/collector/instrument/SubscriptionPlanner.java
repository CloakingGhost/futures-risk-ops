package com.futuresriskops.collector.instrument;

import com.futuresriskops.collector.kis.H0ifasp0Parser;
import com.futuresriskops.collector.kis.H0ifcnt0Parser;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import com.futuresriskops.collector.ws.Subscription;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지금(또는 다음) 세션에 보낼 구독 목록을 만든다.
 *
 * <ul>
 *   <li>종목: AUTO 는 {@link NearMonthPlanner}(근월물, 만기 전날부터 다음 결제월물 겹침), FIXED 는 설정의 symbols 그대로.</li>
 *   <li>TR: 주간장은 체결 H0IFCNT0 + 호가 H0IFASP0(quotes 가 켜졌을 때), 야간장은 체결 H0MFCNT0.
 *       야간 호가(H0MFASP0)는 Day 3 범위에서 뺐다.</li>
 *   <li>야간 종목코드는 주간과 같은 형식(A01612)이다. KIS 야간 마스터(fo_cme_code.mst, 2026-10-29 확인)의 코스피200 선물 코드가
 *       주간 마스터와 같았다. KIS 문서 예시의 101V06·101W9000 은 예전 코드 체계로 본다(실접속으로 최종 확인할 일).</li>
 * </ul>
 * 세션 안이면 그 세션, 밖이면 다음 세션 기준이다. 세션 게이트가 1분 먼저 붙으므로 붙을 때 이미 다음 세션 목록을 보낸다.
 */
public final class SubscriptionPlanner {

    /** 종목을 고르는 방법. */
    public enum Mode {
        /** 결제월 규칙과 마스터로 근월물을 고른다 */
        AUTO,
        /** 설정의 symbols 를 그대로 쓴다(롤오버 없음) */
        FIXED
    }

    /**
     * @param window        기준 세션
     * @param front         근월물 결제월(FIXED 는 비어 있다)
     * @param subscriptions 보낼 구독
     */
    public record Result(SessionCalendar.Window window, Optional<YearMonth> front, List<Subscription> subscriptions) {
    }

    private final SessionCalendar calendar;
    private final NearMonthPlanner nearMonth;
    private final Mode mode;
    private final List<String> fixedSymbols;
    private final boolean quotes;

    public SubscriptionPlanner(SessionCalendar calendar, Mode mode, List<String> fixedSymbols, boolean quotes) {
        if (mode == Mode.FIXED && (fixedSymbols == null || fixedSymbols.isEmpty())) {
            throw new IllegalArgumentException("collector.symbols is required when collector.instruments.mode=FIXED");
        }
        this.calendar = calendar;
        this.nearMonth = new NearMonthPlanner(calendar);
        this.mode = mode;
        this.fixedSymbols = fixedSymbols == null ? List.of() : List.copyOf(fixedSymbols);
        this.quotes = quotes;
    }

    /** 한 달 안에 세션이 없으면(캘린더가 비었을 때) 비어 있다. */
    public Optional<Result> plan(Instant now, FuturesContracts known) {
        return calendar.sessionAt(now).or(() -> calendar.nextOpen(now)).map(w -> plan(w, known));
    }

    public Result plan(SessionCalendar.Window w, FuturesContracts known) {
        List<String> symbols;
        Optional<YearMonth> front;
        if (mode == Mode.FIXED) {
            symbols = fixedSymbols;
            front = Optional.empty();
        } else {
            NearMonthPlanner.Plan p = nearMonth.plan(w.openDate(), w.session(), known);
            symbols = p.contracts().stream().map(FuturesContract::symbol).toList();
            front = Optional.of(p.front());
        }
        List<Subscription> subs = new ArrayList<>();
        for (String s : symbols) {
            if (w.session() == TradingSession.NIGHT) {
                subs.add(new Subscription(H0ifcnt0Parser.NIGHT_TR_ID, s));
            } else {
                subs.add(new Subscription(H0ifcnt0Parser.TR_ID, s));
                if (quotes) {
                    subs.add(new Subscription(H0ifasp0Parser.TR_ID, s));
                }
            }
        }
        return new Result(w, front, List.copyOf(subs));
    }
}
