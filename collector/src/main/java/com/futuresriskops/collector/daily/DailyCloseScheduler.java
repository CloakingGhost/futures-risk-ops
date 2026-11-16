package com.futuresriskops.collector.daily;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DailyCloseJob#nextRunAfter(Instant)} 시각마다 일봉 작업을 한 번씩 돌린다. 스레드는 하나뿐이라 겹쳐 돌지 않는다.
 * 작업이 예외를 던져도 다음 실행은 다시 잡는다. 장 시간 계산은 세션 캘린더가 하고 여기서는 시각만 기다린다.
 *
 * <p>collector 가 16:00 에 꺼져 있었으면 그날은 건너뛴다. 다음 실행이 최근 N영업일을 다시 받아 메운다.
 * {@code run-on-start} 를 켜면 시작 직후에도 한 번 받는다({@link DailyCloseJob#runOnce()}: 장중이면 오늘은 뺀다).
 */
public final class DailyCloseScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DailyCloseScheduler.class);

    private final DailyCloseJob job;
    private final Clock clock;
    private final boolean runOnStart;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daily-close");
        t.setDaemon(true);
        return t;
    });

    public DailyCloseScheduler(DailyCloseJob job, Clock clock, boolean runOnStart) {
        this.job = job;
        this.clock = clock;
        this.runOnStart = runOnStart;
    }

    public void start() {
        if (runOnStart) {
            executor.execute(this::runStartup);
        }
        scheduleNext();
    }

    public void stop() {
        executor.shutdownNow();
    }

    private void scheduleNext() {
        Instant now = clock.instant();
        Instant next = job.nextRunAfter(now);
        LOG.info("next daily close at {}", next);
        executor.schedule(this::runAndReschedule, Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runStartup() {
        try {
            job.runOnce();
        } catch (RuntimeException e) {
            LOG.warn("daily close on start crashed: {}", e.toString());
        }
    }

    private void runAndReschedule() {
        try {
            job.runScheduled();
        } catch (RuntimeException e) {
            LOG.warn("daily close crashed: {}", e.toString());
        } finally {
            if (!executor.isShutdown()) {
                scheduleNext();
            }
        }
    }
}
