package com.futuresriskops.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.futuresriskops.risk.margin.MarginCalculator;
import com.futuresriskops.risk.market.MarketSource;
import com.futuresriskops.risk.market.RiskProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 컨텍스트가 뜨는지와 설정 값만 본다. DB 에는 붙지 않는다(커넥션은 처음 쓸 때 연다). 계정은 테스트용 더미다. */
@SpringBootTest(properties = {"spring.datasource.username=dummy", "spring.datasource.password=dummy"})
class RiskApplicationTest {

    @Autowired
    MarginCalculator calculator;

    @Autowired
    RiskProperties risk;

    @Test
    void contextLoadsWithTheConfiguredMarginRates() {
        assertEquals(0, new BigDecimal("21.750").compareTo(calculator.rates().initialPercent()));
        assertEquals(0, new BigDecimal("14.500").compareTo(calculator.rates().maintenancePercent()));
        assertEquals(0, new BigDecimal("250000").compareTo(calculator.spec().multiplier()));
        assertEquals(MarketSource.SIMULATOR, risk.sourceOrDefault());
    }
}
