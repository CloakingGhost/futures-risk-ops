package com.futuresriskops.collector.daily;

import com.futuresriskops.collector.rest.DailyBar;
import java.time.LocalDate;
import java.util.List;

/** 일봉을 주는 곳. 운영에서는 KIS REST({@code KisDailyBarClient::fetch}), 테스트에서는 합성 값을 주는 가짜다. */
@FunctionalInterface
public interface DailyBarSource {

    /**
     * @param marketCode KIS 시장 분류 코드(F 주간, CM 야간)
     * @param symbol     종목 단축코드
     * @param from       조회 시작일(포함)
     * @param to         조회 종료일(포함)
     */
    List<DailyBar> fetch(String marketCode, String symbol, LocalDate from, LocalDate to);
}
