package com.futuresriskops.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.risk.margin.MarginCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RiskApplicationTest {

    @Autowired
    MarginCalculator calculator;

    @Test
    void contextLoadsWithTheConfiguredMarginRates() {
        assertEquals(0, new BigDecimal("21.750").compareTo(calculator.rates().initialPercent()));
        assertEquals(0, new BigDecimal("14.500").compareTo(calculator.rates().maintenancePercent()));
        assertEquals(0, new BigDecimal("250000").compareTo(calculator.spec().multiplier()));
    }
}
