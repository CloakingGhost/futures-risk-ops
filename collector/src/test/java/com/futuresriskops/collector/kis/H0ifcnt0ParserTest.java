package com.futuresriskops.collector.kis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.H0IFCNT0.Field;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 합성 샘플(src/test/resources/h0ifcnt0/synthetic-frames.txt, 시뮬레이터가 만든 10개)만 쓴다. */
class H0ifcnt0ParserTest {

    private static List<String> samples() throws IOException {
        try (InputStream in = H0ifcnt0ParserTest.class.getResourceAsStream("/h0ifcnt0/synthetic-frames.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .toList();
        }
    }

    private static String data(String frame) {
        return frame.split("\\|", 4)[3];
    }

    private static String withField(String frame, int index, String value) {
        String[] f = data(frame).split("\\^", -1);
        f[index] = value;
        return "0|H0IFCNT0|001|" + String.join("^", f);
    }

    @Test
    void parsesAllTenSyntheticSamplesLikeTheSimulatorDefinesThem() throws IOException {
        List<String> samples = samples();
        assertEquals(10, samples.size());
        for (String frame : samples) {
            List<H0ifcnt0Trade> trades = H0ifcnt0Parser.parse(frame);
            assertEquals(1, trades.size());
            H0ifcnt0Trade t = trades.get(0);
            // 시뮬레이터의 필드 정의(공식 순서)와 같은 위치를 읽는지 대조한다
            H0IFCNT0.Record r = H0IFCNT0.parseFrame(frame);
            assertEquals(r.get(Field.FUTS_SHRN_ISCD), t.symbol());
            assertEquals(new BigDecimal(r.get(Field.FUTS_PRPR)), t.price());
            assertEquals(Integer.parseInt(r.get(Field.LAST_CNQN)), t.qty());
            assertEquals(Long.parseLong(r.get(Field.ACML_VOL)), t.accVolume());
            assertEquals(Long.parseLong(r.get(Field.HTS_OTST_STPL_QTY)), t.openInterest());
            assertEquals(new BigDecimal(r.get(Field.HTS_THPR)), t.theoreticalPrice());
            assertEquals(new BigDecimal(r.get(Field.MRKT_BASIS)), t.marketBasis());
            assertEquals(new BigDecimal(r.get(Field.DPRT)), t.disparityRate());
        }
    }

    @Test
    void aBlankOrBrokenBasisFieldIsNullAndTheTradeIsKept() throws IOException {
        String frame = samples().get(0);
        String blankBasis = withField(frame, H0ifcnt0Parser.IDX_MARKET_BASIS, "");
        String brokenRate = withField(frame, H0ifcnt0Parser.IDX_DISPARITY_RATE, "N/A");

        H0ifcnt0Trade a = H0ifcnt0Parser.parse(blankBasis).get(0);
        assertNull(a.marketBasis());
        assertEquals(H0ifcnt0Parser.parse(frame).get(0).price(), a.price());
        H0ifcnt0Trade b = H0ifcnt0Parser.parse(brokenRate).get(0);
        assertNull(b.disparityRate());
        assertNotNull(b.marketBasis());
    }

    @Test
    void fieldIndexesMatchTheOfficialOrder() {
        assertEquals(H0IFCNT0.FIELD_COUNT, H0ifcnt0Parser.FIELD_COUNT);
        assertEquals(Field.FUTS_SHRN_ISCD.ordinal(), H0ifcnt0Parser.IDX_SYMBOL);
        assertEquals(Field.BSOP_HOUR.ordinal(), H0ifcnt0Parser.IDX_BSOP_HOUR);
        assertEquals(Field.FUTS_PRPR.ordinal(), H0ifcnt0Parser.IDX_PRICE);
        assertEquals(Field.LAST_CNQN.ordinal(), H0ifcnt0Parser.IDX_QTY);
        assertEquals(Field.ACML_VOL.ordinal(), H0ifcnt0Parser.IDX_ACC_VOLUME);
        assertEquals(Field.HTS_OTST_STPL_QTY.ordinal(), H0ifcnt0Parser.IDX_OPEN_INTEREST);
        assertEquals(Field.HTS_THPR.ordinal(), H0ifcnt0Parser.IDX_THEORETICAL_PRICE);
        assertEquals(Field.MRKT_BASIS.ordinal(), H0ifcnt0Parser.IDX_MARKET_BASIS);
        assertEquals(Field.DPRT.ordinal(), H0ifcnt0Parser.IDX_DISPARITY_RATE);
    }

    @Test
    void readsAsManyRecordsAsTheCountSays() throws IOException {
        List<String> s = samples();
        String frame = "0|H0IFCNT0|003|" + data(s.get(0)) + "^" + data(s.get(1)) + "^" + data(s.get(2));
        List<H0ifcnt0Trade> trades = H0ifcnt0Parser.parse(frame);
        assertEquals(3, trades.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(H0ifcnt0Parser.parse(s.get(i)).get(0), trades.get(i));
        }
    }

    @Test
    void rejectsACountThatDoesNotMatchTheFields() throws IOException {
        String frame = samples().get(0).replace("|001|", "|002|");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> H0ifcnt0Parser.parse(frame));
        assertTrue(e.getMessage().contains("record count 2 needs 100 fields but got 50"), e.getMessage());
    }

    @Test
    void rejectsAFrameWithAMissingField() throws IOException {
        String[] f = data(samples().get(0)).split("\\^", -1);
        String frame = "0|H0IFCNT0|001|" + String.join("^", Arrays.copyOf(f, 49));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> H0ifcnt0Parser.parse(frame));
        assertTrue(e.getMessage().contains("got 49"), e.getMessage());
    }

    @Test
    void rejectsAnotherTrId() throws IOException {
        String frame = samples().get(0).replace("|H0IFCNT0|", "|H0IFASP0|");
        assertThrows(IllegalArgumentException.class, () -> H0ifcnt0Parser.parse(frame));
    }

    @Test
    void rejectsEncryptedFrames() throws IOException {
        String frame = "1" + samples().get(0).substring(1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> H0ifcnt0Parser.parse(frame));
        assertTrue(e.getMessage().contains("encrypted"));
    }

    @Test
    void tellsDataFramesFromJsonSystemMessages() throws IOException {
        assertTrue(H0ifcnt0Parser.isDataFrame(samples().get(0)));
        assertFalse(H0ifcnt0Parser.isDataFrame("{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"20261027090000\"}}"));
        assertFalse(H0ifcnt0Parser.isDataFrame(""));
    }

    @Test
    void readsBusinessHourAndKeepsThePriceScale() throws IOException {
        String frame = withField(withField(samples().get(0), H0ifcnt0Parser.IDX_BSOP_HOUR, "235959"),
                H0ifcnt0Parser.IDX_PRICE, "401.50");
        H0ifcnt0Trade t = H0ifcnt0Parser.parse(frame).get(0);
        assertEquals(LocalTime.of(23, 59, 59), t.bsopHour());
        assertEquals("401.50", t.price().toPlainString());
    }

    @Test
    void rejectsNonNumericValuesWithoutEchoingThem() throws IOException {
        String frame = withField(samples().get(0), H0ifcnt0Parser.IDX_QTY, "abc");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> H0ifcnt0Parser.parse(frame));
        assertTrue(e.getMessage().contains("last_cnqn"));
        assertFalse(e.getMessage().contains("abc"));
    }
}
