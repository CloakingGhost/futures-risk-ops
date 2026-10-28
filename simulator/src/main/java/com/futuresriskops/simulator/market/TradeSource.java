package com.futuresriskops.simulator.market;

import com.futuresriskops.simulator.kis.H0IFCNT0;

import java.time.LocalTime;

/**
 * 종목 하나의 체결을 한 건씩 만들어 주는 곳. 시뮬레이터 송출부는 이 인터페이스만 안다.
 *
 * <p>첫 구현은 무작위 행보({@link PriceModel})다. 저장된 체결을 같은 프레임으로 다시 내보내는
 * 리플레이어(Day 16)가 두 번째 구현으로 들어오면, 송출부(속도·지연·끊김)는 고치지 않는다.
 * 한 종목의 소스는 한 번에 한 스레드에서만 부른다.
 */
public interface TradeSource {

    String symbol();

    /** 체결 한 건. {@code now} 는 송출 시각(KST 벽시계)이고, 구현이 체결 시각 필드에 쓸지 정한다. */
    H0IFCNT0.Record next(LocalTime now);

    /** 종목 코드로 소스를 만든다. 처음 구독된 종목마다 한 번 부른다. */
    @FunctionalInterface
    interface Factory {
        TradeSource create(String symbol);
    }
}
