package com.futuresriskops.simulator.kis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.futuresriskops.simulator.kis.H0IFCNT0.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class H0IFCNT0Test {

    /** KIS 공식 샘플(domestic_futureoption_functions_ws.py)의 columns 순서 그대로. */
    private static final String[] OFFICIAL_COLUMNS = {
        "futs_shrn_iscd", "bsop_hour", "futs_prdy_vrss", "prdy_vrss_sign", "futs_prdy_ctrt", "futs_prpr",
        "futs_oprc", "futs_hgpr", "futs_lwpr", "last_cnqn", "acml_vol", "acml_tr_pbmn", "hts_thpr",
        "mrkt_basis", "dprt", "nmsc_fctn_stpl_prc", "fmsc_fctn_stpl_prc", "spead_prc", "hts_otst_stpl_qty",
        "otst_stpl_qty_icdc", "oprc_hour", "oprc_vrss_prpr_sign", "oprc_vrss_nmix_prpr", "hgpr_hour",
        "hgpr_vrss_prpr_sign", "hgpr_vrss_nmix_prpr", "lwpr_hour", "lwpr_vrss_prpr_sign",
        "lwpr_vrss_nmix_prpr", "shnu_rate", "cttr", "esdg", "otst_stpl_rgbf_qty_icdc", "thpr_basis",
        "futs_askp1", "futs_bidp1", "askp_rsqn1", "bidp_rsqn1", "seln_cntg_csnu", "shnu_cntg_csnu",
        "ntby_cntg_csnu", "seln_cntg_smtn", "shnu_cntg_smtn", "total_askp_rsqn", "total_bidp_rsqn",
        "prdy_vol_vrss_acml_vol_rate", "dscs_bltr_acml_qty", "dynm_mxpr", "dynm_llam", "dynm_prc_limt_yn"
    };

    @Test
    void fieldNamesAndOrderMatchTheOfficialSample() {
        assertEquals(50, H0IFCNT0.FIELD_COUNT);
        String[] actual = Arrays.stream(Field.values()).map(Field::officialName).toArray(String[]::new);
        assertEquals(Arrays.asList(OFFICIAL_COLUMNS), Arrays.asList(actual));
    }

    @Test
    void frameHasKisLayout() {
        String[] values = new String[H0IFCNT0.FIELD_COUNT];
        Arrays.fill(values, "1");
        values[0] = "101S12";
        String frame = new H0IFCNT0.Record(values).toFrame();
        assertTrue(frame.startsWith("0|H0IFCNT0|001|101S12^1^"), frame);
        assertEquals(3, frame.chars().filter(c -> c == '|').count());
        assertEquals(49, frame.chars().filter(c -> c == '^').count());
    }

    @Test
    void parseFrameRoundTrips() {
        String[] values = new String[H0IFCNT0.FIELD_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = "v" + i;
        }
        H0IFCNT0.Record r = H0IFCNT0.parseFrame(new H0IFCNT0.Record(values).toFrame());
        assertEquals("v5", r.get(Field.FUTS_PRPR));
        assertEquals(Arrays.asList(values), r.values());
    }

    @Test
    void rejectsWrongFieldCountAndSeparatorsInValues() {
        assertThrows(IllegalArgumentException.class, () -> new H0IFCNT0.Record(new String[3]));
        String[] values = new String[H0IFCNT0.FIELD_COUNT];
        Arrays.fill(values, "1");
        values[3] = "a^b";
        assertThrows(IllegalArgumentException.class, () -> new H0IFCNT0.Record(values));
    }

    @Test
    void parseFrameRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> H0IFCNT0.parseFrame("hello"));
        assertThrows(IllegalArgumentException.class, () -> H0IFCNT0.parseFrame("0|H0STCNT0|001|a^b"));
        assertThrows(IllegalArgumentException.class, () -> H0IFCNT0.parseFrame("0|H0IFCNT0|001|a^b"));
        assertThrows(IllegalArgumentException.class, () -> H0IFCNT0.parseFrame("2|H0IFCNT0|001|a"));
    }
}
