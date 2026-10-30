package com.futuresriskops.collector.instrument;

import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 지금 알고 있는 코스피200 선물 종목(결제월 → 종목). 마스터 파일이나 DB 에서 채운다.
 * 모르는 결제월은 {@link FuturesCode} 규칙으로 만든다. 바꿀 때는 새 객체로 통째로 바꾼다(불변).
 */
public final class FuturesContracts {

    private static final FuturesContracts EMPTY = new FuturesContracts(List.of(), "none");

    private final Map<YearMonth, FuturesContract> byMonth;
    private final String origin;

    public FuturesContracts(Collection<FuturesContract> contracts, String origin) {
        Map<YearMonth, FuturesContract> m = new LinkedHashMap<>();
        for (FuturesContract c : contracts) {
            if (c.contractMonth() != null) {
                m.put(c.contractMonth(), c);
            }
        }
        this.byMonth = Map.copyOf(m);
        this.origin = origin;
    }

    public static FuturesContracts empty() {
        return EMPTY;
    }

    /** 결제월의 종목. 마스터에 없으면 규칙으로 만든 종목. */
    public FuturesContract forMonth(YearMonth m) {
        FuturesContract c = byMonth.get(m);
        return c != null ? c : FuturesContract.fromRule(m);
    }

    public boolean known(YearMonth m) {
        return byMonth.containsKey(m);
    }

    /** 마스터 코드가 규칙과 다른 종목(규칙이 틀렸다는 신호). */
    public List<FuturesContract> ruleMismatches() {
        return byMonth.values().stream().filter(c -> !c.symbol().equals(FuturesCode.forMonth(c.contractMonth())))
                .toList();
    }

    /** 마스터가 최근월물(월물구분 1)이라고 한 종목. */
    public Optional<FuturesContract> masterFront() {
        return byMonth.values().stream().filter(c -> c.monthClass() == 1).findFirst();
    }

    public int size() {
        return byMonth.size();
    }

    /** 어디서 채웠는지(로그용): master, db, none. */
    public String origin() {
        return origin;
    }
}
