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
    /** 가장 최근에 잡은 실행 시각. 타이머가 일찍 깼을 때 같은 시각을 또 잡지 않으려고 기억한다. */
    private volatile Instant planned;
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
        // 타이머는 시계보다 몇 ms 일찍 깰 수 있다. 그때 now 는 방금 정한 실행 시각 직전이라 now 기준으로 다음을 구하면 같은 시각이 또
        // 나와 바로 다시 돈다. 그래서 방금 정한 시각보다 now 가 이르면 그 시각 이후로 구한다.
        Instant after = planned != null && now.isBefore(planned) ? planned : now;
        Instant next = job.nextRunAfter(after);
        planned = next;
        LOG.info("next daily close at {}", next);
        executor.schedule(this::runAndReschedule, Math.max(0, Duration.between(now, next).toMillis()), TimeUnit.MILLISECONDS);
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
