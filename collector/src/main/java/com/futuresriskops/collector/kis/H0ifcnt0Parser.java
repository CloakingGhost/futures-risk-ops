package com.futuresriskops.collector.kis;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 실시간 체결 {@code H0IFCNT0} 텍스트 프레임 파서.
 *
 * <pre>
 *   0|H0IFCNT0|003|필드1^...^필드50^필드1^...^필드50^필드1^...^필드50
 *   암호화여부|TR_ID|데이터건수|데이터(^ 구분, 건수 x 50 개)
 * </pre>
 *
 * <p>필드 순서는 docs/kis-h0ifcnt0.md 표(공식 샘플 koreainvestment/open-trading-api 의 columns 50개)를 따른다.
 * 수집에 쓰는 6개 필드만 꺼낸다. 값의 의미(단위·부호)는 실제 수신으로 확인하기 전까지 가정이다.
 * 형식이 맞지 않으면 {@link IllegalArgumentException} 을 던지고, 메시지에 원문 값은 넣지 않는다.
 */
public final class H0ifcnt0Parser {

    public static final String TR_ID = "H0IFCNT0";
    public static final int FIELD_COUNT = 50;

    // docs/kis-h0ifcnt0.md 의 순번(0부터)
    static final int IDX_SYMBOL = 0;        // futs_shrn_iscd
    static final int IDX_BSOP_HOUR = 1;     // bsop_hour (HHMMSS)
    static final int IDX_PRICE = 5;         // futs_prpr
    static final int IDX_QTY = 9;           // last_cnqn
    static final int IDX_ACC_VOLUME = 10;   // acml_vol
    static final int IDX_OPEN_INTEREST = 18; // hts_otst_stpl_qty

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private H0ifcnt0Parser() {
    }

    /** JSON(구독 응답·PINGPONG)이 아니라 {@code |} 로 나뉜 데이터 프레임인지. */
    public static boolean isDataFrame(String text) {
        return !text.isEmpty() && (text.charAt(0) == '0' || text.charAt(0) == '1') && text.indexOf('|') == 1;
    }

    /** 프레임 하나를 체결 목록으로 바꾼다. 건수가 3이면 3건을 돌려준다. */
    public static List<H0ifcnt0Trade> parse(String frame) {
        String[] head = frame.split("\\|", 4);
        if (head.length != 4) {
            throw new IllegalArgumentException("not a data frame: expected 4 '|' parts but got " + head.length);
        }
        if (head[0].equals("1")) {
            // H0IFCNT0 은 공식 샘플에서 암호화하지 않는 TR 이다. 암호화 프레임이 오면 설정이 잘못된 것으로 본다.
            throw new IllegalArgumentException("encrypted frames are not supported for " + TR_ID);
        }
        if (!head[0].equals("0")) {
            throw new IllegalArgumentException("unexpected encrypt flag: " + head[0]);
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
        String[] fields = head[3].split("\\^", -1);
        if (fields.length != count * FIELD_COUNT) {
            throw new IllegalArgumentException("record count " + count + " needs " + (count * FIELD_COUNT)
                    + " fields but got " + fields.length);
        }
        List<H0ifcnt0Trade> trades = new ArrayList<>(count);
        for (int r = 0; r < count; r++) {
            trades.add(record(fields, r * FIELD_COUNT, r));
        }
        return trades;
    }

    private static H0ifcnt0Trade record(String[] f, int base, int r) {
        String symbol = f[base + IDX_SYMBOL];
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("record " + r + ": empty symbol");
        }
        return new H0ifcnt0Trade(
                symbol,
                time(f[base + IDX_BSOP_HOUR], r),
                decimal(f[base + IDX_PRICE], "futs_prpr", r),
                (int) integer(f[base + IDX_QTY], "last_cnqn", r),
                integer(f[base + IDX_ACC_VOLUME], "acml_vol", r),
                integer(f[base + IDX_OPEN_INTEREST], "hts_otst_stpl_qty", r));
    }

    private static LocalTime time(String v, int r) {
        try {
            return LocalTime.parse(v, HHMMSS);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("record " + r + ": bsop_hour is not HHMMSS");
        }
    }

    private static BigDecimal decimal(String v, String name, int r) {
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("record " + r + ": " + name + " is not a number");
        }
    }

    private static long integer(String v, String name, int r) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("record " + r + ": " + name + " is not an integer");
        }
    }
}
