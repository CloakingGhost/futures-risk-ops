package com.futuresriskops.collector.instrument;

import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.session.TradingSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 종목 마스터를 받아 instrument 에 Upsert 하고, 수집기가 쓸 선물 종목 목록({@link FuturesContracts})을 바꾼다.
 *
 * <p>흐름: 다운로드 → 압축 풀기 → 바이트 줄 단위 cp949 디코딩·검증({@link KisMasterParser}) → Upsert → 메모리 교체.
 * 받지 못하거나 코스피200 선물이 한 종목도 없으면 지금 목록(시작 때는 DB 값)을 그대로 쓴다.
 * 그것도 없으면 결제월 규칙({@link FuturesCode})으로 코드를 만든다. 실패해도 수집은 멈추지 않는다.
 */
public final class MasterRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(MasterRefresher.class);

    /** 마스터 파일 내용(.mst)을 주는 곳. */
    @FunctionalInterface
    public interface Source {
        byte[] download() throws IOException;
    }

    private final Source source;
    private final InstrumentStore store;
    private final SessionCalendar calendar;
    private final Clock clock;
    private volatile FuturesContracts current = FuturesContracts.empty();
    private volatile Instant lastSuccess;

    public MasterRefresher(Source source, InstrumentStore store, SessionCalendar calendar, Clock clock) {
        this.source = source;
        this.store = store;
        this.calendar = calendar;
        this.clock = clock;
    }

    public FuturesContracts current() {
        return current;
    }

    /** 마지막으로 마스터를 반영한 시각. 없으면 null. */
    public Instant lastSuccess() {
        return lastSuccess;
    }

    /** 시작할 때 DB 에 있던 종목을 읽어 둔다(마스터를 받기 전, 또는 못 받을 때 쓴다). */
    public void loadFromStore() {
        try {
            var stored = store.loadFutures();
            if (!stored.isEmpty()) {
                current = new FuturesContracts(stored, "db");
                LOG.info("loaded {} KOSPI200 futures from instrument", stored.size());
            }
        } catch (RuntimeException e) {
            LOG.warn("could not read instrument: {}", e.toString());
        }
    }

    /** 마스터를 한 번 받아 반영한다. 반영했으면 true. */
    public boolean refresh() {
        byte[] content;
        try {
            content = source.download();
        } catch (IOException | RuntimeException e) {
            LOG.warn("master download failed, keeping {} futures from {}: {}", current.size(), current.origin(),
                    e.toString());
            return false;
        }
        KisMasterParser.Result r = KisMasterParser.parse(content);
        LOG.info("master parsed: lines={}, kospi200-futures={}, other-types={}, invalid={}", r.lines(),
                r.contracts().size(), r.otherTypes(), r.invalid());
        if (r.contracts().isEmpty()) {
            LOG.warn("master has no KOSPI200 futures, keeping {} futures from {}", current.size(), current.origin());
            return false;
        }
        Instant now = clock.instant();
        try {
            store.upsertFutures(r.contracts(), now);
        } catch (RuntimeException e) {
            // DB 가 안 돼도 메모리 목록은 바꾼다: 구독할 코드가 더 중요하다
            LOG.warn("instrument upsert failed: {}", e.toString());
        }
        FuturesContracts next = new FuturesContracts(r.contracts(), "master");
        check(next, LocalDate.ofInstant(now, calendar.zone()));
        current = next;
        lastSuccess = now;
        return true;
    }

    /** 규칙·만기 계산과 마스터가 다르면 경고만 한다(마스터 값을 쓴다). */
    private void check(FuturesContracts c, LocalDate today) {
        for (FuturesContract m : c.ruleMismatches()) {
            LOG.warn("master code {} for {} differs from the code rule {}", m.symbol(), m.contractMonth(),
                    FuturesCode.forMonth(m.contractMonth()));
        }
        YearMonth computed = FuturesExpiry.frontMonth(today, TradingSession.DAY, calendar);
        c.masterFront().ifPresent(f -> {
            if (!f.contractMonth().equals(computed)) {
                LOG.warn("master says the front month is {} but the expiry rule says {} for {}", f.contractMonth(),
                        computed, today);
            }
        });
    }
}
