package com.futuresriskops.simulator.market;

import com.futuresriskops.simulator.config.SimulatorConfig;
import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.H0IFCNT0.Field;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Random;

/**
 * 종목 하나의 가짜 시세. 가격은 틱 단위 정수로 들고 있다가 무작위 행보로 움직이고,
 * 체결할 때마다 H0IFCNT0 레코드(50 필드)를 만든다.
 *
 * <p>전부 합성값이다. 실제 시세를 캡처하거나 재배포하지 않는다. 스레드 안전하지 않으므로
 * 한 번에 한 스레드에서만 {@link #next} 를 부른다.
 */
public final class PriceModel {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final double LIMIT_RATIO = 0.10;
    private static final double BASIS_MEAN = 0.25;
    private static final double THEORETICAL_BASIS = 0.20;
    private static final long PREV_DAY_VOLUME = 150_000;

    private final String symbol;
    private final Random rng;
    private final double tick;
    private final double volatilityTicks;
    private final long multiplier;
    private final int maxQty;

    private final long prevCloseTicks;
    private final long minTicks;
    private final long maxTicks;

    private long priceTicks;
    private long openTicks;
    private long highTicks;
    private long lowTicks;
    private String openHour = "";
    private String highHour = "";
    private String lowHour = "";
    private boolean first = true;

    private long accVolume;
    private long accAmount;
    private long openInterest = 50_000;
    private final long startOpenInterest = openInterest;
    private long buyCount;
    private long sellCount;
    private long buyQty;
    private long sellQty;

    public PriceModel(String symbol, SimulatorConfig.Price cfg) {
        this.symbol = symbol;
        long seed = cfg.seed().isPresent() ? cfg.seed().getAsLong() ^ symbol.hashCode() : System.nanoTime();
        this.rng = new Random(seed);
        this.tick = cfg.tickSize();
        this.volatilityTicks = cfg.volatilityTicks();
        this.multiplier = cfg.multiplier();
        this.maxQty = cfg.maxQty();
        this.prevCloseTicks = Math.round(cfg.prevClose() / tick);
        this.minTicks = Math.round(cfg.prevClose() * (1 - LIMIT_RATIO) / tick);
        this.maxTicks = Math.round(cfg.prevClose() * (1 + LIMIT_RATIO) / tick);
        this.priceTicks = clamp(Math.round(cfg.base() / tick));
        this.openTicks = priceTicks;
        this.highTicks = priceTicks;
        this.lowTicks = priceTicks;
    }

    public String symbol() {
        return symbol;
    }

    /** 체결 한 건을 만든다. {@code now} 는 체결 시각(KST 벽시계). */
    public H0IFCNT0.Record next(LocalTime now) {
        String hhmmss = now.format(HHMMSS);

        int step = (int) Math.round(rng.nextGaussian() * volatilityTicks);
        priceTicks = clamp(priceTicks + step);
        if (first) {
            openTicks = priceTicks;
            highTicks = priceTicks;
            lowTicks = priceTicks;
            openHour = hhmmss;
            highHour = hhmmss;
            lowHour = hhmmss;
            first = false;
        }
        if (priceTicks > highTicks) {
            highTicks = priceTicks;
            highHour = hhmmss;
        }
        if (priceTicks < lowTicks) {
            lowTicks = priceTicks;
            lowHour = hhmmss;
        }

        int qty = quantity();
        boolean buyAggressor = rng.nextDouble() < 0.5 + 0.15 * Integer.signum(step);
        if (buyAggressor) {
            buyCount++;
            buyQty += qty;
        } else {
            sellCount++;
            sellQty += qty;
        }
        double price = priceTicks * tick;
        accVolume += qty;
        accAmount += Math.round(price * multiplier) * qty;

        int oiDelta = rng.nextInt(5) - 2;
        if (openInterest + oiDelta < 0) {
            oiDelta = 0;
        }
        openInterest += oiDelta;

        double basis = round2(BASIS_MEAN + rng.nextGaussian() * 0.03);
        double spot = price - basis;
        double theoretical = spot + THEORETICAL_BASIS;
        double disparity = price - theoretical;
        double prevClose = prevCloseTicks * tick;
        double diff = price - prevClose;

        long askTicks = buyAggressor ? priceTicks + 1 : priceTicks;
        long bidTicks = buyAggressor ? priceTicks : priceTicks - 1;

        EnumMap<Field, String> v = new EnumMap<>(Field.class);
        v.put(Field.FUTS_SHRN_ISCD, symbol);
        v.put(Field.BSOP_HOUR, hhmmss);
        v.put(Field.FUTS_PRDY_VRSS, num(diff));
        v.put(Field.PRDY_VRSS_SIGN, sign(priceTicks, prevCloseTicks, true));
        v.put(Field.FUTS_PRDY_CTRT, num(diff / prevClose * 100));
        v.put(Field.FUTS_PRPR, num(price));
        v.put(Field.FUTS_OPRC, num(openTicks * tick));
        v.put(Field.FUTS_HGPR, num(highTicks * tick));
        v.put(Field.FUTS_LWPR, num(lowTicks * tick));
        v.put(Field.LAST_CNQN, Integer.toString(qty));
        v.put(Field.ACML_VOL, Long.toString(accVolume));
        v.put(Field.ACML_TR_PBMN, Long.toString(accAmount));
        v.put(Field.HTS_THPR, num(theoretical));
        v.put(Field.MRKT_BASIS, num(basis));
        v.put(Field.DPRT, num(disparity / theoretical * 100));
        v.put(Field.NMSC_FCTN_STPL_PRC, num(price));
        v.put(Field.FMSC_FCTN_STPL_PRC, num(price + 1.30));
        v.put(Field.SPEAD_PRC, num(-1.30));
        v.put(Field.HTS_OTST_STPL_QTY, Long.toString(openInterest));
        v.put(Field.OTST_STPL_QTY_ICDC, Long.toString(openInterest - startOpenInterest));
        v.put(Field.OPRC_HOUR, openHour);
        v.put(Field.OPRC_VRSS_PRPR_SIGN, sign(priceTicks, openTicks, false));
        v.put(Field.OPRC_VRSS_NMIX_PRPR, num((priceTicks - openTicks) * tick));
        v.put(Field.HGPR_HOUR, highHour);
        v.put(Field.HGPR_VRSS_PRPR_SIGN, sign(priceTicks, highTicks, false));
        v.put(Field.HGPR_VRSS_NMIX_PRPR, num((priceTicks - highTicks) * tick));
        v.put(Field.LWPR_HOUR, lowHour);
        v.put(Field.LWPR_VRSS_PRPR_SIGN, sign(priceTicks, lowTicks, false));
        v.put(Field.LWPR_VRSS_NMIX_PRPR, num((priceTicks - lowTicks) * tick));
        v.put(Field.SHNU_RATE, num(100.0 * buyQty / (buyQty + sellQty)));
        v.put(Field.CTTR, num(sellQty == 0 ? 100.0 : 100.0 * buyQty / sellQty));
        v.put(Field.ESDG, num(disparity));
        v.put(Field.OTST_STPL_RGBF_QTY_ICDC, Integer.toString(oiDelta));
        v.put(Field.THPR_BASIS, num(THEORETICAL_BASIS));
        v.put(Field.FUTS_ASKP1, num(askTicks * tick));
        v.put(Field.FUTS_BIDP1, num(bidTicks * tick));
        v.put(Field.ASKP_RSQN1, Integer.toString(1 + rng.nextInt(300)));
        v.put(Field.BIDP_RSQN1, Integer.toString(1 + rng.nextInt(300)));
        v.put(Field.SELN_CNTG_CSNU, Long.toString(sellCount));
        v.put(Field.SHNU_CNTG_CSNU, Long.toString(buyCount));
        v.put(Field.NTBY_CNTG_CSNU, Long.toString(buyCount - sellCount));
        v.put(Field.SELN_CNTG_SMTN, Long.toString(sellQty));
        v.put(Field.SHNU_CNTG_SMTN, Long.toString(buyQty));
        v.put(Field.TOTAL_ASKP_RSQN, Integer.toString(500 + rng.nextInt(4500)));
        v.put(Field.TOTAL_BIDP_RSQN, Integer.toString(500 + rng.nextInt(4500)));
        v.put(Field.PRDY_VOL_VRSS_ACML_VOL_RATE, num(100.0 * accVolume / PREV_DAY_VOLUME));
        v.put(Field.DSCS_BLTR_ACML_QTY, "0");
        v.put(Field.DYNM_MXPR, num(maxTicks * tick));
        v.put(Field.DYNM_LLAM, num(minTicks * tick));
        v.put(Field.DYNM_PRC_LIMT_YN, "0");

        String[] ordered = new String[H0IFCNT0.FIELD_COUNT];
        for (Field f : Field.values()) {
            String value = v.get(f);
            if (value == null) {
                throw new IllegalStateException("field not filled: " + f);
            }
            ordered[f.ordinal()] = value;
        }
        return new H0IFCNT0.Record(ordered);
    }

    private int quantity() {
        double u = rng.nextDouble();
        int q = 1 + (int) (-Math.log(1 - u) * maxQty / 4.0);
        return Math.min(q, maxQty);
    }

    private long clamp(long ticks) {
        return Math.max(minTicks, Math.min(maxTicks, ticks));
    }

    /** 1상한 2상승 3보합 4하한 5하락. 상·하한은 전일 대비에만 쓴다. */
    private String sign(long current, long reference, boolean useLimit) {
        if (current > reference) {
            return useLimit && current >= maxTicks ? "1" : "2";
        }
        if (current < reference) {
            return useLimit && current <= minTicks ? "4" : "5";
        }
        return "3";
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static String num(double d) {
        double r = Math.round(d * 100.0) / 100.0;
        if (r == 0.0) {
            r = 0.0; // -0.00 방지
        }
        return String.format(Locale.ROOT, "%.2f", r);
    }
}
