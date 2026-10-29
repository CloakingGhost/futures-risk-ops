package com.futuresriskops.collector.ws;

/**
 * 웹소켓 구독 하나: 어떤 실시간 TR 을 어떤 종목으로 받는지.
 *
 * @param trId  실시간 TR (H0IFCNT0 주간 체결, H0IFASP0 주간 호가, H0MFCNT0 야간 체결)
 * @param trKey 종목 단축코드(예: A01612)
 */
public record Subscription(String trId, String trKey) {

    @Override
    public String toString() {
        return trId + ":" + trKey;
    }
}
