package com.futuresriskops.collector.kis;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 지수선물 실시간호가 {@code H0IFASP0} 텍스트 프레임 파서.
 *
 * <pre>
 *   0|H0IFASP0|001|필드1^...^필드38
 * </pre>
 *
 * <p>필드 순서는 공식 샘플(koreainvestment/open-trading-api examples_llm/domestic_futureoption/
 * index_futures_realtime_quote) columns 38개를 따른다. 5단계 호가·잔량과 총잔량만 꺼낸다(호가 건수는 버린다).
 * KIS 문서는 이 TR 을 '모의투자 미지원'이라고 적었다. 모의투자에서 오는지는 실접속으로 확인한다(가정).
 */
public final class H0ifasp0Parser {

    public static final String TR_ID = "H0IFASP0";
    public static final int FIELD_COUNT = 38;
    public static final int LEVELS = 5;

    // 공식 샘플 columns 의 순번(0부터)
    static final int IDX_SYMBOL = 0;          // futs_shrn_iscd
    static final int IDX_BSOP_HOUR = 1;       // bsop_hour
    static final int IDX_ASK_PRICE_1 = 2;     // futs_askp1..5
    static final int IDX_BID_PRICE_1 = 7;     // futs_bidp1..5
    static final int IDX_ASK_QTY_1 = 22;      // askp_rsqn1..5
    static final int IDX_BID_QTY_1 = 27;      // bidp_rsqn1..5
    static final int IDX_TOTAL_ASK_QTY = 34;  // total_askp_rsqn
    static final int IDX_TOTAL_BID_QTY = 35;  // total_bidp_rsqn

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private H0ifasp0Parser() {
    }

    public static List<H0ifasp0Quote> parse(String frame) {
        String[] head = frame.split("\\|", 4);
        if (head.length != 4) {
            throw new IllegalArgumentException("not a data frame: expected 4 '|' parts but got " + head.length);
        }
        if (!head[0].equals("0")) {
            throw new IllegalArgumentException("unexpected encrypt flag for " + TR_ID + ": " + head[0]);
        }
        if (!head[1].equals(TR_ID)) {
            throw new IllegalArgumentException("unexpected tr_id: " + head[1]);
        }
        int count;
        try {
            count = Integer.parseInt(head[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("record count is not a number");
        }
        if (count < 1) {
            throw new IllegalArgumentException("record count must be at least 1 but was " + count);
        }
        String[] f = head[3].split("\\^", -1);
        if (f.length != count * FIELD_COUNT) {
            throw new IllegalArgumentException("record count " + count + " needs " + (count * FIELD_COUNT)
                    + " fields but got " + f.length);
        }
        List<H0ifasp0Quote> out = new ArrayList<>(count);
        for (int r = 0; r < count; r++) {
            out.add(record(f, r * FIELD_COUNT, r));
        }
        return out;
    }

    private static H0ifasp0Quote record(String[] f, int base, int r) {
        String symbol = f[base + IDX_SYMBOL];
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("record " + r + ": empty symbol");
        }
        LocalTime t;
        try {
            t = LocalTime.parse(f[base + IDX_BSOP_HOUR], HHMMSS);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("record " + r + ": bsop_hour is not HHMMSS");
        }
        return new H0ifasp0Quote(symbol, t,
                decimals(f, base + IDX_ASK_PRICE_1, "futs_askp", r), decimals(f, base + IDX_BID_PRICE_1, "futs_bidp", r),
                ints(f, base + IDX_ASK_QTY_1, "askp_rsqn", r), ints(f, base + IDX_BID_QTY_1, "bidp_rsqn", r),
                integer(f[base + IDX_TOTAL_ASK_QTY], "total_askp_rsqn", r),
                integer(f[base + IDX_TOTAL_BID_QTY], "total_bidp_rsqn", r));
    }

    private static List<BigDecimal> decimals(String[] f, int from, String name, int r) {
        List<BigDecimal> out = new ArrayList<>(LEVELS);
        for (int i = 0; i < LEVELS; i++) {
            try {
                out.add(new BigDecimal(f[from + i]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("record " + r + ": " + name + (i + 1) + " is not a number");
            }
        }
        return out;
    }

    private static List<Integer> ints(String[] f, int from, String name, int r) {
        List<Integer> out = new ArrayList<>(LEVELS);
        for (int i = 0; i < LEVELS; i++) {
            out.add((int) integer(f[from + i], name + (i + 1), r));
        }
        return out;
    }

    private static long integer(String v, String name, int r) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("record " + r + ": " + name + " is not an integer");
        }
    }
}
