package com.futuresriskops.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatorConfigTest {

    private static SimulatorConfig load(Map<String, String> env, String... args) {
        return SimulatorConfig.load(args, env);
    }

    @Test
    void defaultsAreValid() {
        SimulatorConfig c = load(Map.of());
        assertEquals("127.0.0.1", c.server().host());
        assertEquals(18080, c.server().port());
        assertEquals(5.0, c.rate().ticksPerSecond());
        assertEquals(0.05, c.price().tickSize());
        assertEquals(c.price().base(), c.price().prevClose());
        assertTrue(c.price().seed().isEmpty());
        assertFalse(c.stall().enabled());
        assertEquals(0.0, c.disconnect().afterSeconds());
    }

    @Test
    void everyOptionHasAValidDefault() {
        // 옵션 목록의 기본값만으로 설정이 만들어져야 한다(문서와 --help 가 이 목록을 쓴다)
        load(Map.of());
        assertEquals(23, SimulatorConfig.OPTIONS.size());
    }

    @Test
    void commandLineOverridesEnvironmentOverridesFile() throws IOException {
        Path file = Files.createTempFile("sim", ".properties");
        try {
            Files.writeString(file, "rate.ticks-per-second=10\ndelay.fixed-ms=100\nserver.port=1111\n",
                    StandardCharsets.UTF_8);
            Map<String, String> env = Map.of("SIM_DELAY_FIXED_MS", "200", "SIM_SERVER_PORT", "2222");
            SimulatorConfig c = load(env, "--config=" + file, "--server.port=3333");
            assertEquals(10.0, c.rate().ticksPerSecond());   // 파일만
            assertEquals(200.0, c.delay().fixedMs());        // 환경변수가 파일을 이김
            assertEquals(3333, c.server().port());           // 명령행이 모두를 이김
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void acceptsSeparateValueAndBareBooleanFlag() {
        SimulatorConfig c = load(Map.of(), "--rate.ticks-per-second", "25", "--disconnect.abrupt");
        assertEquals(25.0, c.rate().ticksPerSecond());
        assertTrue(c.disconnect().abrupt());
    }

    @Test
    void seedMakesPriceModelReproducible() {
        SimulatorConfig c = load(Map.of(), "--price.seed=42");
        assertEquals(42L, c.price().seed().getAsLong());
    }

    @Test
    void rejectsUnknownOption() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load(Map.of(), "--rate.tick-per-second=5"));
        assertTrue(e.getMessage().contains("rate.tick-per-second"));
    }

    @Test
    void rejectsOutOfRangeAndMalformedValues() {
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--rate.ticks-per-second=0"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--rate.ticks-per-second=abc"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--delay.spike-probability=1.5"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--disconnect.probability-per-tick=-0.1"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--server.port=70000"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--disconnect.abrupt=maybe"));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "stray"));
    }

    @Test
    void ignoresUnrelatedEnvironmentVariables() {
        SimulatorConfig c = load(Map.of("SIM_NOT_AN_OPTION", "x", "PATH", "/bin"));
        assertEquals(18080, c.server().port());
    }

    @Test
    void missingConfigFileIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> load(Map.of(), "--config=/no/such/file.properties"));
    }

    @Test
    void stallWindowOnce() {
        SimulatorConfig.Stall s = new SimulatorConfig.Stall(2.0, 3.0, false);
        assertFalse(s.isStalledAt(1.9));
        assertTrue(s.isStalledAt(2.0));
        assertTrue(s.isStalledAt(4.9));
        assertFalse(s.isStalledAt(5.0));
        assertFalse(s.isStalledAt(100.0));
    }

    @Test
    void stallWindowRepeats() {
        SimulatorConfig.Stall s = new SimulatorConfig.Stall(2.0, 3.0, true);
        assertFalse(s.isStalledAt(1.0));
        assertTrue(s.isStalledAt(3.0));
        assertFalse(s.isStalledAt(5.5)); // 두 번째 주기의 정상 구간 (period 5)
        assertTrue(s.isStalledAt(7.5));
    }

    @Test
    void stallDisabledWhenDurationIsZero() {
        assertFalse(new SimulatorConfig.Stall(1.0, 0.0, true).isStalledAt(5.0));
    }

    @Test
    void usageListsEveryOption() {
        String usage = SimulatorConfig.usage();
        for (SimulatorConfig.Option o : SimulatorConfig.OPTIONS) {
            assertTrue(usage.contains("--" + o.key()), o.key());
        }
    }
}
