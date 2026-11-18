package com.futuresriskops.collector.daily;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 일봉 수집 설정({@code collector.daily-close.*}). 시뮬레이터에는 REST 가 없어서 기본은 꺼 두고, KIS 프로필이 켠다.
 *
 * @param enabled       true 면 거래일마다 KIS 일봉을 받아 daily_bar 에 쌓는다. KIS 접속 설정(collector.approval)이 필요하다
 * @param runAt         이 한국 시각부터 받는다. 주간장 마감이 이보다 늦은 날(수능일 등)은 마감 + afterClose 로 미룬다
 * @param afterClose    주간장 마감 뒤 KIS 가 일봉을 확정하길 기다리는 시간(가정: 15분, 첫 실호출로 확인)
 * @param lookbackDays  오늘을 포함해 최근 몇 영업일을 요청할지. 한 번에 최대 100건이라 1~100
 * @param markets       요청할 KIS 시장 분류 코드. F 주간(지수선물), CM 야간선물. 기본은 F 만이다(CM 은 첫 실호출로 되는 것을 확인한 뒤 켠다).
 *                      하나라도 실패하면 그 실행은 성공으로 세지 않는다
 * @param runOnStart    true 면 시작 직후 한 번 받는다(첫 실호출 확인, 며칠 꺼 뒀을 때 보충). 장중이거나 오늘 실행 시각 전이면 오늘은 빼고
 *                      직전 거래일까지만 받는다(진행 중인 값을 확정 일봉으로 저장하지 않으려고)
 */
@ConfigurationProperties(prefix = "collector.daily-close")
public record DailyCloseProperties(@DefaultValue("false") boolean enabled,
                                   @DefaultValue("16:00") LocalTime runAt,
                                   @DefaultValue("15m") Duration afterClose,
                                   @DefaultValue("5") int lookbackDays,
                                   @DefaultValue({"F"}) List<String> markets,
                                   @DefaultValue("false") boolean runOnStart) {

    public DailyCloseProperties {
        if (lookbackDays < 1 || lookbackDays > 100) {
            throw new IllegalArgumentException("collector.daily-close.lookback-days must be 1..100 (KIS returns at most 100 rows per call)");
        }
        if (markets == null || markets.isEmpty()) {
            throw new IllegalArgumentException("collector.daily-close.markets needs at least one of F, CM");
        }
        for (String m : markets) {
            if (!m.equals("F") && !m.equals("CM")) {
                throw new IllegalArgumentException("collector.daily-close.markets allows only F, CM");
            }
        }
        markets = List.copyOf(markets);
    }
}
