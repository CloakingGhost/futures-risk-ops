package com.futuresriskops.simulator.kis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 지수선물 실시간호가(TR ID {@code H0IFASP0}) 합성 프레임.
 *
 * <p>필드 순서는 공식 샘플(examples_llm/domestic_futureoption/index_futures_realtime_quote) columns 38개와 같다.
 * 값은 체결 레코드({@link H0IFCNT0.Record})의 1호가·잔량에서 만든 합성값이다. 2~5호가는 1호가에서 호가 단위만큼 벌리고,
 * 잔량·건수는 1단계 잔량에서 정해진 비율로 만든다. 실제 호가 분포를 흉내 내지 않는다(형식 확인용).
 */
public final class H0IFASP0 {

    public static final String TR_ID = "H0IFASP0";
    public static final int FIELD_COUNT = 38;
    static final int LEVELS = 5;

    private H0IFASP0() {
    }

    /** 체결 레코드 하나로 호가 프레임 하나를 만든다. 같은 체결이면 언제나 같은 프레임이다. */
    public static String fromTrade(H0IFCNT0.Record t, double tickSize) {
        BigDecimal tick = BigDecimal.valueOf(tickSize);
        BigDecimal ask1 = new BigDecimal(t.get(H0IFCNT0.Field.FUTS_ASKP1));
        BigDecimal bid1 = new BigDecimal(t.get(H0IFCNT0.Field.FUTS_BIDP1));
        int askQty1 = Integer.parseInt(t.get(H0IFCNT0.Field.ASKP_RSQN1));
        int bidQty1 = Integer.parseInt(t.get(H0IFCNT0.Field.BIDP_RSQN1));
        List<String> v = new ArrayList<>(FIELD_COUNT);
        v.add(t.get(H0IFCNT0.Field.FUTS_SHRN_ISCD));                // futs_shrn_iscd
        v.add(t.get(H0IFCNT0.Field.BSOP_HOUR));                     // bsop_hour
        for (int i = 0; i < LEVELS; i++) {                          // futs_askp1..5
            v.add(price(ask1.add(tick.multiply(BigDecimal.valueOf(i)))));
        }
        for (int i = 0; i < LEVELS; i++) {                          // futs_bidp1..5
            v.add(price(bid1.subtract(tick.multiply(BigDecimal.valueOf(i)))));
        }
        int[] askQty = levels(askQty1);
        int[] bidQty = levels(bidQty1);
        for (int q : askQty) {                                      // askp_csnu1..5
            v.add(Integer.toString(Math.max(1, q / 3)));
        }
        for (int q : bidQty) {                                      // bidp_csnu1..5
            v.add(Integer.toString(Math.max(1, q / 3)));
        }
        for (int q : askQty) {                                      // askp_rsqn1..5
            v.add(Integer.toString(q));
        }
        for (int q : bidQty) {                                      // bidp_rsqn1..5
            v.add(Integer.toString(q));
        }
        v.add(Integer.toString(sum(askQty) / 3));                   // total_askp_csnu
        v.add(Integer.toString(sum(bidQty) / 3));                   // total_bidp_csnu
        v.add(t.get(H0IFCNT0.Field.TOTAL_ASKP_RSQN));               // total_askp_rsqn
        v.add(t.get(H0IFCNT0.Field.TOTAL_BIDP_RSQN));               // total_bidp_rsqn
        v.add("0");                                                 // total_askp_rsqn_icdc
        v.add("0");                                                 // total_bidp_rsqn_icdc
        return "0|" + TR_ID + "|001|" + String.join("^", v);
    }

    private static int[] levels(int first) {
        int[] q = new int[LEVELS];
        for (int i = 0; i < LEVELS; i++) {
            q[i] = first + i * 7;
        }
        return q;
    }

    private static int sum(int[] q) {
        int s = 0;
        for (int x : q) {
            s += x;
        }
        return s;
    }

    private static String price(BigDecimal p) {
        return p.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
