package com.futuresriskops.simulator;

import com.futuresriskops.simulator.config.SimulatorConfig;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** 실행 진입점. {@code ./gradlew :simulator:run --args="--rate.ticks-per-second=20"} */
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.stream(args).anyMatch(a -> a.equals("--help") || a.equals("-h"))) {
            System.out.print(SimulatorConfig.usage());
            return;
        }
        SimulatorConfig cfg;
        try {
            cfg = SimulatorConfig.load(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }

        SimulatorServer server = new SimulatorServer(cfg, clock(cfg));
        server.start();
        System.out.println("KIS futures simulator listening on ws://" + cfg.server().host() + ":" + server.port()
                + "/tryitout  (any path is accepted)");
        System.out.println(cfg.describe());
        System.out.println("Subscribe with the KIS JSON request (tr_type=1, tr_id=H0IFCNT0|H0MFCNT0|H0IFASP0, "
                + "tr_key=<symbol>); approval_key is not checked - never use a real key here. Ctrl+C to stop.");

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stop.countDown();
        }, "shutdown"));
        stop.await();
    }

    /** clock.start 가 있으면 그 한국 시각부터 흐르는 시계, 없으면 실제 시계. */
    static Clock clock(SimulatorConfig cfg) {
        Clock real = Clock.system(SimulatorServer.KST);
        return cfg.clock().start()
                .map(t -> Clock.offset(real, Duration.between(real.instant(), t.atZone(SimulatorServer.KST).toInstant())))
                .orElse(real);
    }
}
