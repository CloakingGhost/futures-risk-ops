package com.futuresriskops.collector.instrument;

import java.time.YearMonth;

/**
 * 코스피200 선물 단축코드 규칙. 마스터 파일을 못 받았을 때 결제월로 코드를 만든다.
 *
 * <pre>
 *   A 01 6 12  →  A + 상품(01 = 코스피200 선물) + 연도 끝자리 + 월 두 자리   (예: A01612 = 2026년 12월물)
 * </pre>
 * 가정: KIS 마스터 파일(fo_idx_code_mts.mst, 2026-10-28 내려받음)의 코스피200 선물 7종목
 * (A01612, A01703, A01706, A01709, A01712, A01806, A01812)이 모두 이 규칙과 맞았다. KRX 공식 규정으로 확인하지는 않았다.
 * 연도가 한 자리라 10년마다 같은 코드가 다시 나온다. 결제월은 3년 안쪽만 상장되므로 겹치지 않는다.
 * 마스터 파일이 있으면 마스터 값이 우선이고, 다르면 경고한다.
 */
public final class FuturesCode {

    public static final String KOSPI200_FUTURES_PREFIX = "A01";

    private FuturesCode() {
    }

    public static String forMonth(YearMonth m) {
        return KOSPI200_FUTURES_PREFIX + (m.getYear() % 10) + String.format("%02d", m.getMonthValue());
    }
}
