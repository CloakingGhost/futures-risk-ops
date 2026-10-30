package com.futuresriskops.risk.margin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 단일 선물 포지션의 손익, 위탁·유지증거금, 마진콜 여부와 마진콜까지 남은 폭을 계산한다. DB 와 스프링 없이 계산만 한다.
 * 규칙과 사례는 docs/margin-cases.md 에 있고, 테스트가 그 표를 그대로 읽는다.
 *
 * <ul>
 *   <li>손익 = 부호 x (현재가 - 기준가) x 계약 x 승수. 가격이 0.01 단위면 원 단위 정수로 떨어진다</li>
 *   <li>증거금 = 현재가 x 승수 x 계약 x 증거금률, 원 미만 올림(가정: 증거금을 적게 보이지 않게)</li>
 *   <li>마진콜 = 평가예탁금(예탁금 + 손익) &lt; 유지증거금. 추가납부액은 위탁증거금까지 채우는 금액</li>
 *   <li>마진콜 가격 = 평가예탁금과 유지증거금이 같아지는 가격. 매수는 0.01 올림, 매도는 0.01 내림해서 경고가 늦지 않게 한다</li>
 * </ul>
 */
public final class MarginCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal PRICE_STEP = new BigDecimal("0.01");
    private static final MathContext DIVISION = MathContext.DECIMAL64;

    private final ContractSpec spec;
    private final MarginRates rates;

    public MarginCalculator(ContractSpec spec, MarginRates rates) {
        this.spec = spec;
        this.rates = rates;
    }

    public MarginRates rates() {
        return rates;
    }

    public ContractSpec spec() {
        return spec;
    }

    /** 기준가 대비 손익(원). */
    public BigDecimal pnl(Position p, BigDecimal price) {
        return p.side().sign().multiply(price.subtract(p.basePrice())).multiply(contractValue(p.contracts()))
                .setScale(0, RoundingMode.HALF_UP);
    }

    /** 위탁증거금(원, 올림). */
    public BigDecimal initialMargin(int contracts, BigDecimal price) {
        return margin(contracts, price, rates.initialPercent());
    }

    /** 유지증거금(원, 올림). */
    public BigDecimal maintenanceMargin(int contracts, BigDecimal price) {
        return margin(contracts, price, rates.maintenancePercent());
    }

    /**
     * @param deposit 예탁금(원). 기준가 시점의 예탁금이다(일일정산을 반영한 값)
     * @param price   지금 가격
     */
    public MarginResult evaluate(Position p, BigDecimal deposit, BigDecimal price) {
        BigDecimal pnl = pnl(p, price);
        BigDecimal initial = initialMargin(p.contracts(), price);
        BigDecimal maintenance = maintenanceMargin(p.contracts(), price);
        BigDecimal equity = deposit.add(pnl);
        boolean call = equity.compareTo(maintenance) < 0;
        BigDecimal callAmount = call ? initial.subtract(equity) : BigDecimal.ZERO;
        BigDecimal callPrice = marginCallPrice(p, deposit);
        BigDecimal points = null;
        if (callPrice != null) {
            points = call ? BigDecimal.ZERO.setScale(2)
                    : (p.side() == Side.LONG ? price.subtract(callPrice) : callPrice.subtract(price)).max(BigDecimal.ZERO);
        }
        return new MarginResult(pnl, initial, maintenance, equity, call, callAmount, callPrice, points);
    }

    /** 정산가로 손익을 예탁금에 반영하고, 기준가를 정산가로 바꾼다. */
    public Settlement settle(Position p, BigDecimal deposit, BigDecimal settlementPrice) {
        BigDecimal variation = pnl(p, settlementPrice);
        return new Settlement(variation, deposit.add(variation),
                new Position(p.side(), p.contracts(), settlementPrice));
    }

    /**
     * 평가예탁금 = 유지증거금이 되는 가격 P. 예탁금 D, 기준가 E, 계약 x 승수 = V, 유지율 r 일 때
     * 매수: D + (P - E)V = rPV → P = (E - D/V) / (1 - r), 매도: D - (P - E)V = rPV → P = (E + D/V) / (1 + r).
     */
    BigDecimal marginCallPrice(Position p, BigDecimal deposit) {
        BigDecimal value = contractValue(p.contracts());
        BigDecimal r = rates.maintenancePercent().divide(HUNDRED, DIVISION);
        BigDecimal perPoint = deposit.divide(value, DIVISION);
        if (p.side() == Side.LONG) {
            BigDecimal exact = p.basePrice().subtract(perPoint).divide(BigDecimal.ONE.subtract(r), DIVISION);
            if (exact.signum() <= 0) {
                return null;
            }
            return exact.divide(PRICE_STEP, 0, RoundingMode.CEILING).multiply(PRICE_STEP);
        }
        BigDecimal exact = p.basePrice().add(perPoint).divide(BigDecimal.ONE.add(r), DIVISION);
        return exact.divide(PRICE_STEP, 0, RoundingMode.FLOOR).multiply(PRICE_STEP);
    }

    private BigDecimal margin(int contracts, BigDecimal price, BigDecimal percent) {
        return price.multiply(contractValue(contracts)).multiply(percent).divide(HUNDRED, 0, RoundingMode.CEILING);
    }

    private BigDecimal contractValue(int contracts) {
        return spec.multiplier().multiply(BigDecimal.valueOf(contracts));
    }
}
