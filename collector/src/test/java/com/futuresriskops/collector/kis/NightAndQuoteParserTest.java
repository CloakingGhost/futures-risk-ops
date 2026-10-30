package com.futuresriskops.collector.kis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.simulator.kis.H0IFASP0;
import com.futuresriskops.simulator.kis.H0IFCNT0;
import com.futuresriskops.simulator.kis.H0IFCNT0.Field;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 야간 체결 H0MFCNT0(49필드)과 주간 호가 H0IFASP0(38필드). 시뮬레이터가 만든 합성 프레임만 쓴다. */
class NightAndQuoteParserTest {

    private static List<H0IFCNT0.Record> samples() throws IOException {
        try (InputStream in = NightAndQuoteParserTest.class.getResourceAsStream("/h0ifcnt0/synthetic-frames.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .map(H0IFCNT0::parseFrame)
                    .toList();
        }
    }

    @Test
    void nightFramesHave49FieldsAndParseToTheSameTrade() throws IOException {
        for (H0IFCNT0.Record r : samples()) {
            String night = r.toNightFrame();
            assertTrue(night.startsWith("0|H0MFCNT0|001|"));
            assertEquals(49, night.split("\\|", 4)[3].split("\\^", -1).length);
            assertEquals(H0ifcnt0Parser.parse(r.toFrame()), H0ifcnt0Parser.parse(night));
        }
    }

    @Test
    void aNightFrameWithTheDayFieldCountIsRejected() throws IOException {
        String dayFieldsUnderNightId = samples().get(0).toFrame().replace("|H0IFCNT0|", "|H0MFCNT0|");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> H0ifcnt0Parser.parse(dayFieldsUnderNightId));
        assertTrue(e.getMessage().contains("needs 49 fields"), e.getMessage());
    }

    @Test
    void quoteFramesParseFiveLevels() throws IOException {
        H0IFCNT0.Record r = samples().get(0);
        H0ifasp0Quote q = H0ifasp0Parser.parse(H0IFASP0.fromTrade(r, 0.05)).get(0);
        assertEquals(r.get(Field.FUTS_SHRN_ISCD), q.symbol());
        assertEquals(5, q.askPrices().size());
        assertEquals(new BigDecimal(r.get(Field.FUTS_ASKP1)), q.askPrices().get(0));
        assertEquals(new BigDecimal(r.get(Field.FUTS_BIDP1)), q.bidPrices().get(0));
        assertEquals(q.askPrices().get(0).add(new BigDecimal("0.20")), q.askPrices().get(4));
        assertEquals(q.bidPrices().get(0).subtract(new BigDecimal("0.20")), q.bidPrices().get(4));
        assertEquals(Integer.parseInt(r.get(Field.ASKP_RSQN1)), q.askQtys().get(0));
        assertEquals(Long.parseLong(r.get(Field.TOTAL_BIDP_RSQN)), q.totalBidQty());
    }

    @Test
    void twoQuoteRecordsInOneFrame() throws IOException {
        String a = H0IFASP0.fromTrade(samples().get(0), 0.05);
        String b = H0IFASP0.fromTrade(samples().get(1), 0.05);
        String two = "0|H0IFASP0|002|" + a.split("\\|", 4)[3] + "^" + b.split("\\|", 4)[3];
        assertEquals(2, H0ifasp0Parser.parse(two).size());
    }

    @Test
    void malformedQuotesAreRejectedWithoutTheRawValue() throws IOException {
        String good = H0IFASP0.fromTrade(samples().get(0), 0.05);
        String[] f = good.split("\\|", 4)[3].split("\\^", -1);
        f[3] = "abc";
        String bad = "0|H0IFASP0|001|" + String.join("^", f);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> H0ifasp0Parser.parse(bad));
        assertEquals("record 0: futs_askp2 is not a number", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> H0ifasp0Parser.parse("0|H0IFASP0|001|A01612^090000"));
    }

    @Test
    void dataFrameTrIdIsReadWithoutParsing() {
        assertEquals("H0IFASP0", KisDataFrame.trId("0|H0IFASP0|001|x"));
        assertEquals("", KisDataFrame.trId("{\"header\":{}}"));
    }
}
