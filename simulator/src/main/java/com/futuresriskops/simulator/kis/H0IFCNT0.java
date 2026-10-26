package com.futuresriskops.simulator.kis;

import java.util.Arrays;
import java.util.List;

/**
 * KIS 국내선물옵션 "지수선물 실시간체결가" (TR ID {@code H0IFCNT0}) 메시지 형식.
 *
 * <p>필드 이름과 순서는 한국투자증권 공식 샘플(koreainvestment/open-trading-api,
 * {@code domestic_futureoption_functions_ws.py} 의 {@code index_futures_realtime_conclusion})의
 * columns 목록 50개와 같다. 프레임 구조는 공식 {@code kis_auth.py} 수신부와 같다:
 *
 * <pre>
 *   0|H0IFCNT0|001|필드1^필드2^...^필드50
 *   암호화여부|TR_ID|데이터건수|데이터(^ 구분)
 * </pre>
 *
 * <p>주의: 필드의 <b>값 의미(부호 표기, 단위 등)</b>는 실제 수신 캡처 없이 만든 합성값이다.
 * 형식(개수·순서·구분자)만 실제와 같다고 본다.
 */
public final class H0IFCNT0 {

    public static final String TR_ID = "H0IFCNT0";
    public static final char FIELD_SEPARATOR = '^';
    public static final char FRAME_SEPARATOR = '|';

    private H0IFCNT0() {
    }

    /** 공식 순서 그대로의 필드 목록. */
    public enum Field {
        FUTS_SHRN_ISCD("futs_shrn_iscd", "선물 단축 종목코드"),
        BSOP_HOUR("bsop_hour", "영업 시간(HHMMSS)"),
        FUTS_PRDY_VRSS("futs_prdy_vrss", "선물 전일 대비"),
        PRDY_VRSS_SIGN("prdy_vrss_sign", "전일 대비 부호(1상한 2상승 3보합 4하한 5하락)"),
        FUTS_PRDY_CTRT("futs_prdy_ctrt", "선물 전일 대비율"),
        FUTS_PRPR("futs_prpr", "선물 현재가"),
        FUTS_OPRC("futs_oprc", "선물 시가"),
        FUTS_HGPR("futs_hgpr", "선물 최고가"),
        FUTS_LWPR("futs_lwpr", "선물 최저가"),
        LAST_CNQN("last_cnqn", "최종 거래량(이번 체결 수량)"),
        ACML_VOL("acml_vol", "누적 거래량"),
        ACML_TR_PBMN("acml_tr_pbmn", "누적 거래 대금"),
        HTS_THPR("hts_thpr", "HTS 이론가"),
        MRKT_BASIS("mrkt_basis", "시장 베이시스"),
        DPRT("dprt", "괴리율"),
        NMSC_FCTN_STPL_PRC("nmsc_fctn_stpl_prc", "근월물 약정가"),
        FMSC_FCTN_STPL_PRC("fmsc_fctn_stpl_prc", "원월물 약정가"),
        SPEAD_PRC("spead_prc", "스프레드 가격"),
        HTS_OTST_STPL_QTY("hts_otst_stpl_qty", "미결제 약정 수량"),
        OTST_STPL_QTY_ICDC("otst_stpl_qty_icdc", "미결제 약정 수량 증감"),
        OPRC_HOUR("oprc_hour", "시가 시간"),
        OPRC_VRSS_PRPR_SIGN("oprc_vrss_prpr_sign", "시가 대비 현재가 부호"),
        OPRC_VRSS_NMIX_PRPR("oprc_vrss_nmix_prpr", "시가 대비 지수 현재가"),
        HGPR_HOUR("hgpr_hour", "최고가 시간"),
        HGPR_VRSS_PRPR_SIGN("hgpr_vrss_prpr_sign", "최고가 대비 현재가 부호"),
        HGPR_VRSS_NMIX_PRPR("hgpr_vrss_nmix_prpr", "최고가 대비 지수 현재가"),
        LWPR_HOUR("lwpr_hour", "최저가 시간"),
        LWPR_VRSS_PRPR_SIGN("lwpr_vrss_prpr_sign", "최저가 대비 현재가 부호"),
        LWPR_VRSS_NMIX_PRPR("lwpr_vrss_nmix_prpr", "최저가 대비 지수 현재가"),
        SHNU_RATE("shnu_rate", "매수 비율"),
        CTTR("cttr", "체결강도"),
        ESDG("esdg", "괴리도"),
        OTST_STPL_RGBF_QTY_ICDC("otst_stpl_rgbf_qty_icdc", "미결제 약정 직전 수량 증감"),
        THPR_BASIS("thpr_basis", "이론 베이시스"),
        FUTS_ASKP1("futs_askp1", "선물 매도호가1"),
        FUTS_BIDP1("futs_bidp1", "선물 매수호가1"),
        ASKP_RSQN1("askp_rsqn1", "매도호가 잔량1"),
        BIDP_RSQN1("bidp_rsqn1", "매수호가 잔량1"),
        SELN_CNTG_CSNU("seln_cntg_csnu", "매도 체결 건수"),
        SHNU_CNTG_CSNU("shnu_cntg_csnu", "매수 체결 건수"),
        NTBY_CNTG_CSNU("ntby_cntg_csnu", "순매수 체결 건수"),
        SELN_CNTG_SMTN("seln_cntg_smtn", "총 매도 수량"),
        SHNU_CNTG_SMTN("shnu_cntg_smtn", "총 매수 수량"),
        TOTAL_ASKP_RSQN("total_askp_rsqn", "총 매도호가 잔량"),
        TOTAL_BIDP_RSQN("total_bidp_rsqn", "총 매수호가 잔량"),
        PRDY_VOL_VRSS_ACML_VOL_RATE("prdy_vol_vrss_acml_vol_rate", "전일 거래량 대비 누적 거래량 비율"),
        DSCS_BLTR_ACML_QTY("dscs_bltr_acml_qty", "동시호가 등 누적 수량"),
        DYNM_MXPR("dynm_mxpr", "실시간 상한가"),
        DYNM_LLAM("dynm_llam", "실시간 하한가"),
        DYNM_PRC_LIMT_YN("dynm_prc_limt_yn", "실시간 가격제한 구분");

        private final String officialName;
        private final String description;

        Field(String officialName, String description) {
            this.officialName = officialName;
            this.description = description;
        }

        /** KIS 공식 샘플의 컬럼 이름(소문자 스네이크). */
        public String officialName() {
            return officialName;
        }

        public String description() {
            return description;
        }
    }

    public static final int FIELD_COUNT = Field.values().length;

    /** 한 건의 체결 레코드. 값은 모두 문자열이고 순서는 {@link Field} 순서다. */
    public static final class Record {
        private final String[] values;

        public Record(String[] values) {
            if (values.length != FIELD_COUNT) {
                throw new IllegalArgumentException(
                        "H0IFCNT0 needs " + FIELD_COUNT + " fields but got " + values.length);
            }
            for (String v : values) {
                if (v == null || v.indexOf(FIELD_SEPARATOR) >= 0 || v.indexOf(FRAME_SEPARATOR) >= 0) {
                    throw new IllegalArgumentException("field value is null or contains a separator: " + v);
                }
            }
            this.values = values.clone();
        }

        public String get(Field f) {
            return values[f.ordinal()];
        }

        public List<String> values() {
            return List.of(values);
        }

        /** {@code 0|H0IFCNT0|001|a^b^...} 형태의 웹소켓 텍스트 프레임. */
        public String toFrame() {
            return "0" + FRAME_SEPARATOR + TR_ID + FRAME_SEPARATOR + "001" + FRAME_SEPARATOR
                    + String.join(String.valueOf(FIELD_SEPARATOR), values);
        }

        @Override
        public String toString() {
            return toFrame();
        }
    }

    /** 프레임 문자열을 레코드로 되돌린다. 형식이 맞지 않으면 {@link IllegalArgumentException}. */
    public static Record parseFrame(String frame) {
        String[] head = frame.split("\\|", 4);
        if (head.length < 4) {
            throw new IllegalArgumentException("not a data frame (needs 4 '|' parts): " + abbreviate(frame));
        }
        if (!head[0].equals("0") && !head[0].equals("1")) {
            throw new IllegalArgumentException("unexpected encrypt flag: " + head[0]);
        }
        if (!head[1].equals(TR_ID)) {
            throw new IllegalArgumentException("unexpected tr_id: " + head[1]);
        }
        if (!head[2].equals("001")) {
            throw new IllegalArgumentException("simulator frames carry exactly one record, got count " + head[2]);
        }
        String[] fields = head[3].split("\\^", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + FIELD_COUNT + " fields but got " + fields.length + ": " + Arrays.toString(fields));
        }
        return new Record(fields);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
