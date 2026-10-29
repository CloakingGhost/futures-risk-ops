package com.futuresriskops.collector.store;

import com.futuresriskops.collector.session.SessionCalendar;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이미 쌓인 tick_trade 의 거래일·세션을 세션 캘린더 규칙으로 다시 계산한다.
 * Day 1 은 임시 규칙(수신 한국 날짜)으로 채웠고, 야간장 거래일 규칙은 캘린더 설정(night_trade_date)에 따라 달라진다.
 * 규칙을 바꾼 뒤에도 다시 돌리면 된다.
 *
 * <p>체결 일시(traded_at)는 이미 날짜까지 정해져 있으므로 그대로 두고 거래일·세션만 바꾼다.
 * 바꾼 결과가 이미 있는 행과 같은 체결(UNIQUE)이 되면 그 행은 그대로 두고 충돌로 센다(지우지 않는다).
 */
public class TradeDateBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(TradeDateBackfill.class);

    public record Result(long scanned, long updated, long conflicts) {
    }

    private record Change(long id, LocalDate tradeDate, String session) {
    }

    private final JdbcTemplate jdbc;

    public TradeDateBackfill(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result run(SessionCalendar calendar) {
        List<Change> changes = new ArrayList<>();
        long[] scanned = {0};
        jdbc.query("SELECT id, traded_at, trade_date, session FROM tick_trade ORDER BY id", rs -> {
            scanned[0]++;
            SessionCalendar.Placement p = calendar.classify(
                    rs.getTimestamp(2).toInstant().atZone(calendar.zone()));
            LocalDate oldDate = rs.getDate(3).toLocalDate();
            String oldSession = rs.getString(4);
            if (!p.tradeDate().equals(oldDate) || !p.session().name().equals(oldSession)) {
                changes.add(new Change(rs.getLong(1), p.tradeDate(), p.session().name()));
            }
        });
        long updated = 0;
        long conflicts = 0;
        for (Change c : changes) {
            try {
                updated += jdbc.update("UPDATE tick_trade SET trade_date = ?, session = ? WHERE id = ?",
                        Date.valueOf(c.tradeDate()), c.session(), c.id());
            } catch (DuplicateKeyException e) {
                conflicts++;
            }
        }
        Result r = new Result(scanned[0], updated, conflicts);
        LOG.info("trade date backfill: scanned={}, updated={}, conflicts={}", r.scanned(), r.updated(), r.conflicts());
        return r;
    }
}
