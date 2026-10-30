package com.futuresriskops.collector.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 마스터 파일 형식(9필드, | 구분, cp949)을 흉내 낸 합성 줄로 확인한다. 실제 파일 내용이 아니다. */
class KisMasterParserTest {

    static final String SYNTHETIC = String.join("\r\n",
            "1|A01612|KR4A016C0004|F 202612| |00000.00|1|2001|KOSPI200",
            "1|A01703|KR4A01730006|F 202703| |00000.00|2|2001|KOSPI200",
            "B|A05611|KR4A056B0006|미니F 202611| |00000.00|1|2001|KOSPI200",
            "4|D0161201|KR4D016C73S1|SP 2612-2703| |00000.00|1|2001|KOSPI200",
            "2|201WB385|KR4201WB3854|C 202611 385.0| |00385.00|1|2001|KOSPI200",
            "1|A01706|broken line with too few fields",
            "") + "\r\n";

    static byte[] cp949(String s) {
        return s.getBytes(KisMasterParser.CP949);
    }

    @Test
    void keepsOnlyKospi200FuturesAndCountsTheRest() {
        KisMasterParser.Result r = KisMasterParser.parse(cp949(SYNTHETIC));
        assertEquals(List.of("A01612", "A01703"), r.contracts().stream().map(FuturesContract::symbol).toList());
        FuturesContract dec = r.contracts().get(0);
        assertEquals(YearMonth.of(2026, 12), dec.contractMonth());
        assertEquals("KR4A016C0004", dec.standardCode());
        assertEquals(1, dec.monthClass());
        assertEquals(6, r.lines());
        assertEquals(3, r.otherTypes());
        assertEquals(1, r.invalid());
    }

    @Test
    void koreanNamesOfAnyByteWidthDoNotShiftLaterFields() {
        // 한글 이름이 길어도(2바이트 글자 여러 개) 구분자로 나누므로 결제월·월물구분이 그대로다
        String line = "1|A01709|KR4A01790000|코스피200 선물 F 202709| |00000.00|4|2001|KOSPI200\n";
        KisMasterParser.Result r = KisMasterParser.parse(cp949(line));
        assertEquals(1, r.contracts().size());
        assertEquals(YearMonth.of(2027, 9), r.contracts().get(0).contractMonth());
        assertEquals(4, r.contracts().get(0).monthClass());
        assertEquals("코스피200 선물 F 202709", r.contracts().get(0).name());
    }

    @Test
    void undecodableBytesAndBadValuesAreCountedAsInvalid() {
        byte[] bad = {'1', '|', (byte) 0xFF, (byte) 0xFF, '\n'};
        assertEquals(1, KisMasterParser.parse(bad).invalid());
        String badMonth = "1|A01612|KR4A016C0004|F 2026XX| |00000.00|1|2001|KOSPI200\n";
        String badCode = "1|101W12|KR4101W12000|F 202612| |00000.00|1|2001|KOSPI200\n";
        String otherUnderlying = "1|A01612|KR4A016C0004|F 202612| |00000.00|1|2002|KOSDAQ150\n";
        KisMasterParser.Result r = KisMasterParser.parse(cp949(badMonth + badCode + otherUnderlying));
        assertEquals(0, r.contracts().size());
        assertEquals(3, r.invalid());
    }

    @Test
    void emptyInputHasNoContracts() {
        KisMasterParser.Result r = KisMasterParser.parse(new byte[0]);
        assertEquals(0, r.lines());
        assertEquals(0, r.contracts().size());
    }

    @Test
    void theDownloaderTakesTheMstFileOutOfTheZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("x".getBytes());
            zip.putNextEntry(new ZipEntry("fo_idx_code_mts.mst"));
            zip.write(cp949(SYNTHETIC));
        }
        byte[] mst = MasterDownloader.unzipMst(out.toByteArray());
        assertEquals(2, KisMasterParser.parse(mst).contracts().size());
    }
}
