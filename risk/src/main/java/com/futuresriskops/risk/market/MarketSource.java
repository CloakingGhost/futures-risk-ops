package com.futuresriskops.risk.market;

/** tick_trade.source 값. collector 의 출처 구분과 같다. 출처가 다른 데이터는 섞어 계산하지 않는다. */
public enum MarketSource {
    KIS,
    KIS_PAPER,
    SIMULATOR,
    REPLAY
}
