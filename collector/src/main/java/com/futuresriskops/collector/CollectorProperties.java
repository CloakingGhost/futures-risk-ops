package com.futuresriskops.collector;

import com.futuresriskops.collector.store.TickTrade;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * collector 설정(application.yml 의 {@code collector.*}). 시뮬레이터와 KIS 는 이 값만 다르다.
 * 키·시크릿은 여기 두지 않는다. KIS 모드일 때 환경변수에서 읽는다.
 *
 * @param enabled            false 면 웹소켓 수집을 시작하지 않는다(마이그레이션·거래일 재계산만 할 때)
 * @param source             tick_trade.source 에 적을 값
 * @param wsUrl              웹소켓 주소
 * @param symbols            구독할 종목(tr_key)
 * @param reconnect          재접속 대기(지수 백오프 + 지터)
 * @param session            세션 캘린더와 장 시간 접속
 * @param stallThreshold     구독 중 세션 안에서 이 시간 넘게 체결이 없으면 수신 공백으로 센다
 * @param backfillTradeDates true 면 시작할 때 쌓인 tick_trade 의 거래일·세션을 캘린더 규칙으로 다시 계산한다
 * @param approval           접속키를 어디서 받는지
 * @param batch              DB 배치 적재
 */
@ConfigurationProperties(prefix = "collector")
public record CollectorProperties(boolean enabled, TickTrade.Source source, String wsUrl, List<String> symbols,
                                  Reconnect reconnect, Session session, Duration stallThreshold,
                                  boolean backfillTradeDates, Approval approval, Batch batch) {

    /** 1, 2, 4 ... 초로 늘려 maxBackoff 에서 멈추고, 매번 0~maxJitter 를 더한다. */
    public record Reconnect(Duration initialBackoff, Duration maxBackoff, Duration maxJitter) {
    }

    /**
     * @param calendar     캘린더 YAML 위치(스프링 리소스 표기). 기본은 jar 에 넣은 config/session-calendar.yaml
     * @param gating       true 면 세션 시간에만 접속한다(KIS 프로필). 시뮬레이터 기본은 false
     * @param connectLead  세션 시작 몇 분 전에 접속할지
     */
    public record Session(String calendar, boolean gating, Duration connectLead) {
    }

    /**
     * @param mode         NONE 은 시뮬레이터(검사 없음), KIS 는 /oauth2/Approval
     * @param appKeyEnv    KIS 모드에서 앱키를 읽을 환경변수 이름(값이 아니다). 프로필마다 다르다
     * @param appSecretEnv KIS 모드에서 시크릿을 읽을 환경변수 이름
     */
    public record Approval(Mode mode, String restBaseUrl, String appKeyEnv, String appSecretEnv) {
        public enum Mode {
            NONE,
            KIS
        }
    }

    public record Batch(int queueCapacity, int maxSize, long flushIntervalMs) {
    }
}
