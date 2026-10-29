package com.futuresriskops.collector.monitor;

import com.futuresriskops.collector.session.TradingSession;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * 빈틈은 두 가지로 나눠 센다.
 * <ul>
 *   <li>재접속 빈틈: 새 연결 뒤 그 종목의 첫 새 체결에서 생긴 빈틈. 끊긴 동안 놓친 체결이라 확실한 누락이다. 알람은 이쪽에 건다.</li>
 *   <li>연결 중 빈틈: 연결이 이어지는 동안 생긴 빈틈. 조용한 중단으로 버려진 체결일 수도 있지만, KIS 가 같은 순간의 체결을
 *       묶어 마지막 수량만 보내는 경우(가정, 2026-10-28 모의투자 주간장에서 체결이 몰린 초마다 1~4계약씩 관찰)에도 생긴다.</li>
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

    /**
     * @param missing        GAP 일 때 놓친 수량, 아니면 0
     * @param afterReconnect 새 연결 뒤 그 종목의 첫 새 체결이었는가
     */
    public record Observation(Kind kind, long missing, boolean afterReconnect) {
    }

    private record Key(String symbol, LocalDate tradeDate, TradingSession session) {
    }

    private final Map<String, Key> currentKey = new HashMap<>();
    private final Map<Key, Long> lastAcc = new HashMap<>();
    /** 마지막 새 연결 뒤 새 체결을 이미 받은 종목. 여기 없는 종목의 다음 새 체결이 '재접속 직후 첫 체결'이다. */
    private final Set<String> seenSinceSubscribe = new HashSet<>();
    private boolean subscribedOnce;

    private long expectedVolume;
    private long receivedVolume;
    private long missingVolume;
    private long gaps;
    private long reconnectGaps;
    private long reconnectMissing;
    private long repeats;
    private long inconsistent;

    /** 새 연결을 열 때(재접속 포함) 부른다. 그 뒤 종목마다 첫 새 체결의 빈틈을 재접속 빈틈으로 센다. */
    public synchronized void onNewConnection() {
        subscribedOnce = true;
        seenSinceSubscribe.clear();
    }

    private boolean firstAfterResubscribe(String symbol) {
        return subscribedOnce && !seenSinceSubscribe.contains(symbol);
    }

    public synchronized Observation observe(String symbol, LocalDate tradeDate, TradingSession session, int qty,
                                            long accVolume) {
        Key key = new Key(symbol, tradeDate, session);
        Key prevKey = currentKey.put(symbol, key);
        if (prevKey != null && !prevKey.equals(key)) {
            lastAcc.remove(prevKey); // 세션이 바뀌면 이전 세션 기준은 버린다
        }
        boolean afterReconnect = firstAfterResubscribe(symbol);
        Long last = lastAcc.get(key);
        if (last == null) {
            lastAcc.put(key, accVolume);
            seenSinceSubscribe.add(symbol);
            return new Observation(Kind.FIRST, 0, afterReconnect);
        }
        long delta = accVolume - last;
        if (delta <= 0) {
            repeats++; // 재전송된 체결은 '첫 새 체결' 표시를 쓰지 않는다
            return new Observation(Kind.REPEAT, 0, false);
        }
        seenSinceSubscribe.add(symbol);
        lastAcc.put(key, accVolume);
        expectedVolume += delta;
        if (delta == qty) {
            receivedVolume += qty;
            return new Observation(Kind.OK, 0, afterReconnect);
        }
        if (delta > qty) {
            receivedVolume += qty;
            long missing = delta - qty;
            missingVolume += missing;
            gaps++;
            if (afterReconnect) {
                reconnectGaps++;
                reconnectMissing += missing;
                LOG.warn("missed trades on {} {} {} while disconnected: acc_volume rose by {}, this trade has qty {} "
                        + "(missing {})", symbol, tradeDate, session, delta, qty, missing);
            } else {
                long n = gaps - reconnectGaps;
                if (n <= 5 || n % 1000 == 0) {
                    LOG.info("acc_volume gap on {} while connected: rose by {}, this trade has qty {} (missing {}, "
                            + "stream gap #{}; bundled trades or a silent stall)", symbol, delta, qty, missing, n);
                }
            }
            return new Observation(Kind.GAP, missing, afterReconnect);
        }
        receivedVolume += delta;
        inconsistent++;
        if (inconsistent <= 20) {
            LOG.warn("inconsistent trade on {}: acc_volume rose by {} but qty is {}", symbol, delta, qty);
        }
        return new Observation(Kind.INCONSISTENT, 0, afterReconnect);
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

    /** 누락이 생긴 횟수(빈틈 수, 재접속·연결 중 합계). */
    public synchronized long gapCount() {
        return gaps;
    }

    /** 재접속 직후 빈틈 수. */
    public synchronized long reconnectGapCount() {
        return reconnectGaps;
    }

    /** 재접속 직후 빈틈의 누락 수량(끊긴 동안 놓친 것). */
    public synchronized long reconnectMissingVolume() {
        return reconnectMissing;
    }

    /** 연결 중 빈틈 수. */
    public synchronized long streamGapCount() {
        return gaps - reconnectGaps;
    }

    /** 연결 중 빈틈의 누락 수량(묶음 전송이면 실제 손실이 아니다). */
    public synchronized long streamMissingVolume() {
        return missingVolume - reconnectMissing;
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

    /** 재접속 누락 수량 / 기대 수량. 알람 기준. */
    public synchronized double reconnectMissingRate() {
        return expectedVolume == 0 ? 0.0 : (double) reconnectMissing / expectedVolume;
    }
}
