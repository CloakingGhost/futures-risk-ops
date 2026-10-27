package com.futuresriskops.collector;

import com.futuresriskops.collector.store.TickTrade;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * collector 설정(application.yml 의 {@code collector.*}). 시뮬레이터와 KIS 는 이 값만 다르다.
 * 키·시크릿은 여기 두지 않는다. KIS 모드일 때 환경변수에서 읽는다.
 *
 * @param enabled  false 면 웹소켓 수집을 시작하지 않는다(마이그레이션만 확인할 때)
 * @param source   tick_trade.source 에 적을 값
 * @param wsUrl    웹소켓 주소
 * @param symbols  구독할 종목(tr_key)
 * @param approval 접속키를 어디서 받는지
 * @param batch    DB 배치 적재
 */
@ConfigurationProperties(prefix = "collector")
public record CollectorProperties(boolean enabled, TickTrade.Source source, String wsUrl, List<String> symbols,
                                  Duration maxReconnectBackoff, Approval approval, Batch batch) {

    /** @param mode NONE 은 시뮬레이터(검사 없음), KIS 는 /oauth2/Approval */
    public record Approval(Mode mode, String restBaseUrl) {
        public enum Mode {
            NONE,
            KIS
        }
    }

    public record Batch(int queueCapacity, int maxSize, long flushIntervalMs) {
    }
}
