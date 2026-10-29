package com.futuresriskops.collector.kis;

/** KIS 실시간 데이터 프레임({@code 암호화여부|TR_ID|건수|데이터}) 공통 처리. */
public final class KisDataFrame {

    private KisDataFrame() {
    }

    /** JSON(구독 응답·PINGPONG)이 아니라 {@code |} 로 나뉜 데이터 프레임인지. */
    public static boolean isDataFrame(String text) {
        return !text.isEmpty() && (text.charAt(0) == '0' || text.charAt(0) == '1') && text.indexOf('|') == 1;
    }

    /** 데이터 프레임의 TR_ID. 형식이 아니면 빈 문자열. */
    public static String trId(String frame) {
        int a = frame.indexOf('|');
        int b = a < 0 ? -1 : frame.indexOf('|', a + 1);
        return b < 0 ? "" : frame.substring(a + 1, b);
    }
}
