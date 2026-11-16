package com.futuresriskops.collector.daily;

import com.futuresriskops.collector.CollectorProperties;
import com.futuresriskops.collector.IngestService;
import com.futuresriskops.collector.instrument.FuturesContracts;
import com.futuresriskops.collector.rest.KisAccessTokenClient;
import com.futuresriskops.collector.rest.KisCredentials;
import com.futuresriskops.collector.rest.KisDailyBarClient;
import com.futuresriskops.collector.session.SessionCalendar;
import com.futuresriskops.collector.store.TickTradeRepository;
import java.time.Clock;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 일봉 수집(collector.daily-close.enabled=true)일 때만 만든다. KIS REST 접속 정보(도메인, 환경변수 이름)는
 * 웹소켓 접속키와 같은 collector.approval 설정을 쓴다. 시뮬레이터 설정(approval.mode=NONE)에서 켜면 시작할 때 실패한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "collector.daily-close", name = "enabled", havingValue = "true")
class DailyCloseConfiguration {

    @Bean
    KisDailyBarClient kisDailyBarClient(CollectorProperties props, OkHttpClient http) {
        CollectorProperties.Approval a = props.approval();
        if (a == null || a.mode() != CollectorProperties.Approval.Mode.KIS) {
            throw new IllegalStateException("collector.daily-close.enabled needs a KIS profile (collector.approval.mode=KIS): the simulator has no REST API");
        }
        KisCredentials credentials = KisCredentials.fromEnv(System.getenv(), a.appKeyEnv(), a.appSecretEnv());
        KisAccessTokenClient tokens = new KisAccessTokenClient(http, a.restBaseUrl(), credentials, Clock.systemUTC());
        return new KisDailyBarClient(http, a.restBaseUrl(), credentials, tokens);
    }

    @Bean
    DailyBarRepository dailyBarRepository(JdbcTemplate jdbc, TickTradeRepository trades) {
        return new DailyBarRepository(jdbc, trades::instrumentId);
    }

    @Bean
    DailyCloseJob dailyCloseJob(KisDailyBarClient client, DailyBarRepository repository, SessionCalendar calendar,
                                CollectorProperties props, DailyCloseProperties daily,
                                ObjectProvider<IngestService> ingest) {
        IngestService service = ingest.getIfAvailable();
        // 수집이 꺼져 있으면(collector.enabled=false) 마스터가 없으므로 결제월 규칙으로 만든 코드를 쓴다
        return new DailyCloseJob(client::fetch, repository, calendar,
                () -> service != null ? service.master().current() : FuturesContracts.empty(), props.source(),
                Clock.systemUTC(), daily);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    DailyCloseScheduler dailyCloseScheduler(DailyCloseJob job, DailyCloseProperties daily) {
        return new DailyCloseScheduler(job, Clock.systemUTC(), daily.runOnStart());
    }
}
