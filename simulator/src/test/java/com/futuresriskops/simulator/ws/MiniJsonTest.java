package com.futuresriskops.simulator.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    private static final String KIS_SUBSCRIBE = """
            {"header":{"approval_key":"dummy","custtype":"P","tr_type":"1","content-type":"utf-8"},
             "body":{"input":{"tr_id":"H0IFCNT0","tr_key":"101S12"}}}""";

    @Test
    void readsKisSubscribeRequest() {
        Object json = MiniJson.parse(KIS_SUBSCRIBE);
        assertEquals("1", MiniJson.getString(json, "header", "tr_type"));
        assertEquals("H0IFCNT0", MiniJson.getString(json, "body", "input", "tr_id"));
        assertEquals("101S12", MiniJson.getString(json, "body", "input", "tr_key"));
        assertNull(MiniJson.getString(json, "body", "input", "missing"));
        assertNull(MiniJson.getString(json, "nope", "input"));
    }

    @Test
    void parsesNestedValues() {
        Object json = MiniJson.parse("{\"a\":[1,2.5,\"x\",true,false,null,{\"b\":\"c\"}],\"e\":{}}");
        Map<?, ?> m = (Map<?, ?>) json;
        List<?> a = (List<?>) m.get("a");
        assertEquals(7, a.size());
        assertEquals(1.0, a.get(0));
        assertEquals(Boolean.TRUE, a.get(3));
        assertNull(a.get(5));
        assertEquals(0, ((Map<?, ?>) m.get("e")).size());
    }

    @Test
    void handlesEscapesAndUnicode() {
        Object json = MiniJson.parse("{\"k\":\"line\\n\\\"q\\\" \\u00e9 한글\"}");
        assertEquals("line\n\"q\" é 한글", MiniJson.getString(json, "k"));
    }

    @Test
    void quoteEscapesAndRoundTrips() {
        String raw = "a\"b\\c\nd\te\u0001";
        assertEquals(raw, MiniJson.getString(MiniJson.parse("{\"k\":" + MiniJson.quote(raw) + "}"), "k"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} extra"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{a:1}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":\"unterminated}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("not json"));
    }
}
