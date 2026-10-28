package com.futuresriskops.collector.monitor;

import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 누락 감지. 종목·거래일·세션마다 직전 누적거래량을 기억하고, 새 체결이 오면
 * (누적거래량 증가분) 과 (이번 체결 수량) 을 비교한다.
 *
 * <ul>
 *   <li>증가분 = 수량: 정상(OK).</li>
 *   <li>증가분 &gt; 수량: 그 사이 체결을 놓쳤다(GAP). 차이를 누락 수량으로 더한다. 끊긴 동안이나 조용한 중단 뒤에 생긴다.</li>
 *   <li>증가분 ≤ 0: 이미 본 누적거래량이 다시 왔다(REPEAT). 재접속 직후 서버가 다시 보낸 체결 등. DB UNIQUE 가 버릴 행이다.</li>
 *   <li>0 &lt; 증가분 &lt; 수량: 앞뒤가 맞지 않는다(INCONSISTENT). 순서가 뒤바뀌었거나 필드 가정이 틀렸을 때.</li>
 *   <li>그 종목·세션의 첫 체결(FIRST)은 기준만 잡는다. 수집기를 켜기 전의 거래는 누락으로 세지 않는다.</li>
 * </ul>
 * 누락률 = 누락 수량 / 기대 수량(누적거래량이 늘어난 양의 합). 누적거래량이 세션마다 0부터 다시 시작한다는 것은 가정이고,
 * 세션이 바뀌면 기준을 새로 잡으므로 그 가정이 틀려도 누락으로 잘못 세지는 않는다.
 */
public final class GapDetector {

    private static final Logger LOG = LoggerFactory.getLogger(GapDetector.class);

    public enum Kind {
        FIRST,
        OK,
        GAP,
        REPEAT,
        INCONSISTENT
    }

    /** @param missing GAP 일 때 놓친 수량, 아니면 0 */
    public record Observation(Kind kind, long missing) {
    }

    private record Key(String symbol, LocalDate tradeDate, TradingSession session) {
    }

    private final Map<String, Key> currentKey = new HashMap<>();
    private final Map<Key, Long> lastAcc = new HashMap<>();

    private long expectedVolume;
    private long receivedVolume;
    private long missingVolume;
    private long gaps;
    private long repeats;
    private long inconsistent;

    public synchronized Observation observe(String symbol, LocalDate tradeDate, TradingSession session, int qty,
                                            long accVolume) {
        Key key = new Key(symbol, tradeDate, session);
        Key prevKey = currentKey.put(symbol, key);
        if (prevKey != null && !prevKey.equals(key)) {
            lastAcc.remove(prevKey); // 세션이 바뀌면 이전 세션 기준은 버린다
        }
        Long last = lastAcc.get(key);
        if (last == null) {
            lastAcc.put(key, accVolume);
            return new Observation(Kind.FIRST, 0);
        }
        long delta = accVolume - last;
        if (delta <= 0) {
            repeats++;
            return new Observation(Kind.REPEAT, 0);
        }
        lastAcc.put(key, accVolume);
        expectedVolume += delta;
        if (delta == qty) {
            receivedVolume += qty;
            return new Observation(Kind.OK, 0);
        }
        if (delta > qty) {
            receivedVolume += qty;
            long missing = delta - qty;
            missingVolume += missing;
            gaps++;
            if (gaps <= 20 || gaps % 100 == 0) {
                LOG.warn("missed trades on {} {} {}: acc_volume rose by {} but this trade has qty {} (missing {}, gap #{})",
                        symbol, tradeDate, session, delta, qty, missing, gaps);
            }
            return new Observation(Kind.GAP, missing);
        }
        receivedVolume += delta;
        inconsistent++;
        if (inconsistent <= 20) {
            LOG.warn("inconsistent trade on {}: acc_volume rose by {} but qty is {}", symbol, delta, qty);
        }
        return new Observation(Kind.INCONSISTENT, 0);
    }

    /** 누적거래량이 늘어난 양의 합(첫 체결 기준 이후). */
    public synchronized long expectedVolume() {
        return expectedVolume;
    }

    public synchronized long receivedVolume() {
        return receivedVolume;
    }

    public synchronized long missingVolume() {
        return missingVolume;
    }

    /** 누락이 생긴 횟수(빈틈 수). */
    public synchronized long gapCount() {
        return gaps;
    }

    public synchronized long repeatCount() {
        return repeats;
    }

    public synchronized long inconsistentCount() {
        return inconsistent;
    }

    /** 누락 수량 / 기대 수량. 기대 수량이 0 이면 0. */
    public synchronized double missingRate() {
        return expectedVolume == 0 ? 0.0 : (double) missingVolume / expectedVolume;
    }
}
