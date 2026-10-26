package com.futuresriskops.simulator.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.simulator.config.SimulatorConfig;
import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.H0IFCNT0.Field;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceModelTest {

    private static SimulatorConfig.Price price(String... args) {
        return SimulatorConfig.load(args, Map.of()).price();
    }

    private static double d(H0IFCNT0.Record r, Field f) {
        return Double.parseDouble(r.get(f));
    }

    @Test
    void recordsHaveAllFieldsAndKisFormattedValues() {
        PriceModel m = new PriceModel("101S12", price("--price.seed=1"));
        H0IFCNT0.Record r = m.next(LocalTime.of(9, 30, 5));
        assertEquals("101S12", r.get(Field.FUTS_SHRN_ISCD));
        assertEquals("093005", r.get(Field.BSOP_HOUR));
        assertTrue(r.get(Field.FUTS_PRPR).matches("\\d+\\.\\d{2}"), r.get(Field.FUTS_PRPR));
        assertEquals("093005", r.get(Field.OPRC_HOUR));
        assertEquals(H0IFCNT0.FIELD_COUNT, r.values().size());
        H0IFCNT0.parseFrame(r.toFrame()); // 프레임으로 만들었다가 다시 읽힌다
    }

    @Test
    void invariantsHoldOverManyTrades() {
        SimulatorConfig.Price cfg = price("--price.seed=7", "--price.volatility-ticks=4", "--price.max-qty=20");
        PriceModel m = new PriceModel("101S12", cfg);
        double tick = cfg.tickSize();
        double prevAccVol = 0;
        double prevAmount = 0;
        long sumQty = 0;
        for (int i = 0; i < 5000; i++) {
            H0IFCNT0.Record r = m.next(LocalTime.of(9, 0).plusSeconds(i / 10));
            double p = d(r, Field.FUTS_PRPR);
            double hi = d(r, Field.FUTS_HGPR);
            double lo = d(r, Field.FUTS_LWPR);
            // 가격은 호가 단위의 배수, 고가 >= 현재가 >= 저가, 상·하한 안
            assertEquals(0.0, Math.abs(p / tick - Math.rint(p / tick)), 1e-6, "tick multiple " + p);
            assertTrue(lo <= p && p <= hi, "low<=price<=high at " + i);
            assertTrue(p <= d(r, Field.DYNM_MXPR) && p >= d(r, Field.DYNM_LLAM), "within limits");
            // 누적값은 줄지 않고, 누적 거래량은 체결 수량의 합과 같다
            int qty = Integer.parseInt(r.get(Field.LAST_CNQN));
            assertTrue(qty >= 1 && qty <= 20, "qty " + qty);
            sumQty += qty;
            double acc = d(r, Field.ACML_VOL);
            assertEquals((double) sumQty, acc);
            assertTrue(acc >= prevAccVol);
            assertTrue(d(r, Field.ACML_TR_PBMN) >= prevAmount);
            prevAccVol = acc;
            prevAmount = d(r, Field.ACML_TR_PBMN);
            // 호가: 매도 >= 현재가 >= 매수, 매도 > 매수
            assertTrue(d(r, Field.FUTS_ASKP1) >= p && p >= d(r, Field.FUTS_BIDP1));
            assertTrue(d(r, Field.FUTS_ASKP1) > d(r, Field.FUTS_BIDP1));
            // 체결 건수 합 = 지금까지의 체결 수
            long buys = Long.parseLong(r.get(Field.SHNU_CNTG_CSNU));
            long sells = Long.parseLong(r.get(Field.SELN_CNTG_CSNU));
            assertEquals(i + 1L, buys + sells);
            assertEquals(buys - sells, Long.parseLong(r.get(Field.NTBY_CNTG_CSNU)));
            assertTrue(r.get(Field.PRDY_VRSS_SIGN).matches("[1-5]"));
            assertTrue(r.toFrame().indexOf('\n') < 0);
        }
    }

    @Test
    void signMatchesDirectionAgainstPreviousClose() {
        PriceModel m = new PriceModel("101S12", price("--price.seed=3", "--price.volatility-ticks=3"));
        for (int i = 0; i < 2000; i++) {
            H0IFCNT0.Record r = m.next(LocalTime.NOON);
            double diff = d(r, Field.FUTS_PRPR) - 400.00;
            String sign = r.get(Field.PRDY_VRSS_SIGN);
            if (diff > 1e-9) {
                assertTrue(sign.equals("2") || sign.equals("1"), sign);
            } else if (diff < -1e-9) {
                assertTrue(sign.equals("5") || sign.equals("4"), sign);
            } else {
                assertEquals("3", sign);
            }
            assertEquals(diff, d(r, Field.FUTS_PRDY_VRSS), 0.0051);
        }
    }

    @Test
    void sameSeedGivesSamePricePathAndDifferentSeedDoesNot() {
        List<String> a = path(price("--price.seed=11"));
        List<String> b = path(price("--price.seed=11"));
        List<String> c = path(price("--price.seed=12"));
        assertEquals(a, b);
        assertTrue(!a.equals(c));
    }

    @Test
    void priceNeverLeavesDailyLimitEvenWithHugeVolatility() {
        PriceModel m = new PriceModel("101S12", price("--price.seed=5", "--price.volatility-ticks=500"));
        for (int i = 0; i < 3000; i++) {
            H0IFCNT0.Record r = m.next(LocalTime.NOON);
            double p = d(r, Field.FUTS_PRPR);
            assertTrue(p >= 360.0 - 1e-9 && p <= 440.0 + 1e-9, "price " + p);
        }
    }

    private static List<String> path(SimulatorConfig.Price cfg) {
        PriceModel m = new PriceModel("101S12", cfg);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            H0IFCNT0.Record r = m.next(LocalTime.NOON);
            out.add(r.get(Field.FUTS_PRPR) + "/" + r.get(Field.LAST_CNQN));
        }
        return out;
    }
}
