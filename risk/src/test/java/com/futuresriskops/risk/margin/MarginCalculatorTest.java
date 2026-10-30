package com.futuresriskops.risk.margin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 사례 표(MarginCasesTest) 밖의 입력 검사. 잘못된 설정·입력은 계산하지 않고 바로 거절한다. */
class MarginCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void maintenanceRateAboveTheInitialRateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarginRates(d("14.500"), d("21.750")));
    }

    @Test
    void missingZeroOrOverHundredRatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarginRates(null, d("14.500")));
        assertThrows(IllegalArgumentException.class, () -> new MarginRates(d("21.750"), d("0")));
        assertThrows(IllegalArgumentException.class, () -> new MarginRates(d("100.01"), d("14.500")));
    }

    @Test
    void zeroContractsOrANonPositivePriceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Position(Side.LONG, 0, d("400.00")));
        assertThrows(IllegalArgumentException.class, () -> new Position(Side.SHORT, 1, d("0")));
    }

    @Test
    void theSettledPositionMeasuresTheNextDayFromTheSettlementPrice() {
        var calc = new MarginCalculator(ContractSpec.KOSPI200_FUTURES, new MarginRates(d("21.750"), d("14.500")));
        Settlement s = calc.settle(new Position(Side.LONG, 1, d("400.00")), d("20000000"), d("398.00"));
        assertEquals(0, d("398.00").compareTo(s.next().basePrice()));
        // 다음 날 손익은 정산가 398.00 에서 잰다: (399.00 - 398.00) x 250,000
        assertEquals(0, d("250000").compareTo(calc.pnl(s.next(), d("399.00"))));
    }
}
