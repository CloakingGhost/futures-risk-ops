package com.futuresriskops.collector.instrument;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KIS 지수선물옵션 종목 마스터(fo_idx_code_mts.mst) 파서. 코스피200 선물(상품종류 1)만 꺼낸다.
 *
 * <p>형식은 공식 샘플(koreainvestment/open-trading-api stocks_info/domestic_index_future_code.py,
 * 종목마스터정보(지수선물옵션).h)을 따른다. 한 줄에 9필드, {@code |} 구분, cp949.
 * <pre>
 *   상품종류|단축코드|표준코드|한글종목명|ATM구분|행사가|월물구분코드|기초자산 단축코드|기초자산 명
 *   1|A01612|KR4A016C0004|F 202612| |00000.00|1|2001|KOSPI200        (합성 예시, 실제 파일과 같은 모양)
 * </pre>
 *
 * <p>한글 바이트 밀림을 피하려고 바이트 그대로 줄을 나눈 뒤 줄마다 cp949 로 디코딩한다. 고정 길이로 자르지 않고
 * 구분자로 나누므로 한글 폭이 달라도 다음 필드가 밀리지 않는다. 디코딩이 안 되거나 필드 수가 9가 아닌 줄,
 * 코드·결제월이 형식에 맞지 않는 줄은 버리고 센다(원문은 남기지 않는다).
 */
public final class KisMasterParser {

    public static final Charset CP949 = Charset.forName("MS949");
    static final int FIELD_COUNT = 9;
    static final String TYPE_INDEX_FUTURES = "1";
    static final String KOSPI200_UNDERLYING = "KOSPI200";
    // 코스피200 선물 상품코드 A01 + 세 자리(지금은 연도 1자리 + 월 2자리). 형식이 바뀌어도 받도록 영숫자를 허용한다
    private static final Pattern SYMBOL = Pattern.compile("A01[0-9A-Z]{3}");
    private static final Pattern MONTH_IN_NAME = Pattern.compile("(\\d{4})(\\d{2})\\s*$");

    /**
     * @param contracts   코스피200 선물 종목(파일 순서)
     * @param lines       읽은 줄 수(빈 줄 제외)
     * @param otherTypes  상품종류가 지수선물이 아니어서 건너뛴 줄 수(스프레드, 옵션, 미니 등)
     * @param invalid     형식이 틀려 버린 줄 수
     */
    public record Result(List<FuturesContract> contracts, int lines, int otherTypes, int invalid) {
    }

    private KisMasterParser() {
    }

    public static Result parse(byte[] content) {
        List<FuturesContract> out = new ArrayList<>();
        int lines = 0;
        int other = 0;
        int invalid = 0;
        int start = 0;
        for (int i = 0; i <= content.length; i++) {
            if (i < content.length && content[i] != '\n') {
                continue;
            }
            int end = i;
            if (end > start && content[end - 1] == '\r') {
                end--;
            }
            if (end > start) {
                lines++;
                String line = decode(content, start, end - start);
                if (line == null) {
                    invalid++;
                } else {
                    String[] f = line.split("\\|", -1);
                    if (f.length != FIELD_COUNT) {
                        invalid++;
                    } else if (!f[0].trim().equals(TYPE_INDEX_FUTURES)) {
                        other++;
                    } else {
                        FuturesContract c = contract(f);
                        if (c == null) {
                            invalid++;
                        } else {
                            out.add(c);
                        }
                    }
                }
            }
            start = i + 1;
        }
        return new Result(List.copyOf(out), lines, other, invalid);
    }

    private static FuturesContract contract(String[] f) {
        String symbol = f[1].trim();
        String name = f[3].trim();
        if (!SYMBOL.matcher(symbol).matches() || !KOSPI200_UNDERLYING.equals(f[8].trim())) {
            return null;
        }
        Matcher m = MONTH_IN_NAME.matcher(name);
        if (!m.find()) {
            return null;
        }
        int monthClass;
        YearMonth month;
        try {
            month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            monthClass = Integer.parseInt(f[6].trim());
        } catch (RuntimeException e) {
            return null;
        }
        return new FuturesContract(symbol, f[2].trim(), name, month, monthClass);
    }

    private static String decode(byte[] b, int off, int len) {
        CharsetDecoder d = CP949.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return d.decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
