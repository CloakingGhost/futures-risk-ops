package com.futuresriskops.simulator.kis;

import com.futuresriskops.simulator.ws.MiniJson;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 구독 응답·PINGPONG 같은 시스템 메시지(JSON). 형식은 KIS 공식 샘플의 {@code system_resp} 가 읽는 구조를 따른다.
 *
 * <ul>
 *   <li>구독 응답: {@code header.tr_id/tr_key/encrypt}, {@code body.rt_cd/msg_cd/msg1}</li>
 *   <li>PINGPONG: {@code header.tr_id = "PINGPONG"}, {@code header.datetime}</li>
 * </ul>
 */
public final class KisFrames {

    public static final String MSG_SUBSCRIBE_OK = "SUBSCRIBE SUCCESS";
    public static final String MSG_UNSUBSCRIBE_OK = "UNSUBSCRIBE SUCCESS";

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private KisFrames() {
    }

    public static String ok(String trId, String trKey, String msg1) {
        return "{\"header\":{\"tr_id\":" + MiniJson.quote(trId)
                + ",\"tr_key\":" + MiniJson.quote(trKey)
                + ",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":\"0\",\"msg_cd\":\"OPSP0000\",\"msg1\":"
                + MiniJson.quote(msg1) + "}}";
    }

    /** 시뮬레이터가 거절할 때 쓰는 응답. msg_cd 는 시뮬레이터 자체 코드이며 KIS 실제 코드가 아니다. */
    public static String error(String trId, String trKey, String msg1) {
        return "{\"header\":{\"tr_id\":" + MiniJson.quote(trId == null ? "" : trId)
                + ",\"tr_key\":" + MiniJson.quote(trKey == null ? "" : trKey)
                + ",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":\"1\",\"msg_cd\":\"SIM0001\",\"msg1\":"
                + MiniJson.quote(msg1) + "}}";
    }

    public static String pingPong(Clock clock) {
        String now = DATETIME.format(clock.instant().atZone(ZoneId.of("Asia/Seoul")));
        return "{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"" + now + "\"}}";
    }
}
