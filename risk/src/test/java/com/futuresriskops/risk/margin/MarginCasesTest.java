package com.futuresriskops.risk.margin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/margin-cases.md 의 표를 그대로 읽어 계산과 대조한다. 사례의 계산 근거는 그 문서에 있다.
 * 표 한 줄 = 테스트 하나. 금액은 쉼표를 빼고 읽고, '-' 는 값 없음이다.
 */
class MarginCasesTest {

    private static final ContractSpec SPEC = ContractSpec.KOSPI200_FUTURES;

    private static List<String[]> rows(String prefix) throws IOException {
        String file = System.getProperty("marginCasesFile", "../docs/margin-cases.md");
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            if (!line.startsWith("| " + prefix)) {
                continue;
            }
            String[] cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|"))
                    .map(String::trim).toArray(String[]::new);
            if (cells[0].matches(prefix + "\\d+")) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /** 사례 번호와 내용을 테스트 이름으로, 한 줄 전체를 값으로 넘긴다. */
    private static Stream<Arguments> cases(String prefix) throws IOException {
        return rows(prefix).stream().map(c -> Arguments.of(c[0] + " " + c[1], c));
    }

    static Stream<Arguments> positionCases() throws IOException {
        return cases("A");
    }

    static Stream<Arguments> settlementCases() throws IOException {
        return cases("B");
    }

    private static BigDecimal num(String cell) {
        return new BigDecimal(cell.replace(",", ""));
    }

    private static Side side(String cell) {
        return switch (cell) {
            case "매수" -> Side.LONG;
            case "매도" -> Side.SHORT;
            default -> throw new IllegalArgumentException("unknown side " + cell);
        };
    }

    private static void assertAmount(String expected, BigDecimal actual, String what) {
        assertEquals(0, num(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    @Test
    void theDocumentHoldsTwentyCases() throws IOException {
        assertEquals(17, rows("A").size());
        assertEquals(3, rows("B").size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positionCases")
    void positionCase(String name, String[] c) {
        // # | 내용 | 방향 | 계약 | 기준가 | 현재가 | 예탁금 | 위탁% | 유지% | 손익 | 위탁증거금 | 유지증거금 | 평가예탁금 | 마진콜 | 추가납부 | 마진콜 가격 | 남은 폭
        var calc = new MarginCalculator(SPEC, new MarginRates(num(c[7]), num(c[8])));
        var p = new Position(side(c[2]), Integer.parseInt(c[3]), num(c[4]));
        MarginResult r = calc.evaluate(p, num(c[6]), num(c[5]));
        String id = c[0];
        assertAmount(c[9], r.pnl(), id + " pnl");
        assertAmount(c[10], r.initialMargin(), id + " initial margin");
        assertAmount(c[11], r.maintenanceMargin(), id + " maintenance margin");
        assertAmount(c[12], r.equity(), id + " equity");
        assertEquals("Y".equals(c[13]), r.marginCall(), id + " margin call");
        assertAmount(c[14], r.callAmount(), id + " call amount");
        if ("-".equals(c[15])) {
            assertNull(r.marginCallPrice(), id + " margin call price");
            assertNull(r.pointsToMarginCall(), id + " points to margin call");
        } else {
            assertAmount(c[15], r.marginCallPrice(), id + " margin call price");
            assertAmount(c[16], r.pointsToMarginCall(), id + " points to margin call");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settlementCases")
    void settlementCase(String name, String[] c) {
        // # | 내용 | 방향 | 계약 | 처음 예탁금 | 정산가 | 날마다 정산금 | 마지막 예탁금
        var calc = new MarginCalculator(SPEC, new MarginRates(new BigDecimal("21.750"), new BigDecimal("14.500")));
        String[] prices = c[5].split("→");
        String[] variations = c[6].split(";");
        var p = new Position(side(c[2]), Integer.parseInt(c[3]), num(prices[0].trim()));
        BigDecimal deposit = num(c[4]);
        for (int i = 1; i < prices.length; i++) {
            Settlement s = calc.settle(p, deposit, num(prices[i].trim()));
            assertAmount(variations[i - 1].trim(), s.variation(), c[0] + " day " + i);
            p = s.next();
            deposit = s.deposit();
        }
        assertAmount(c[7], deposit, c[0] + " last deposit");
    }
}
