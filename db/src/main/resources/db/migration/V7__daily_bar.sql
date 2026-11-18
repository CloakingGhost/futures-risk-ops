-- V7: Day 7. KIS 일봉(선물옵션기간별시세 FHKIF03020100)을 저장하는 daily_bar.
--
-- 일봉은 tick_trade 에서 직접 계산하지 않는다. KIS 가 주는 값을 KIS 에서 받아 그대로 둔다(2026-11-16 결정).
-- 같은 (종목, 영업일, 시장, 출처) 를 다시 받으면 collector 가 ON CONFLICT DO UPDATE 로 덮어쓴다(fetched_at 도 갱신).
-- 미결제약정 열은 두지 않는다. 미결제는 체결 메시지의 KIS 값(tick_trade.open_interest)을 그대로 쓴다.
--
-- market 은 KIS 시장 분류 코드다. F 지수선물(주간), CM 야간선물.
-- 가정(첫 실호출로 확인): 일봉은 주간장 기준이다, CM 으로도 받아진다. 확인 결과는 docs/kis-daily-bar.md.
-- source 는 tick_trade 와 같은 출처 구분이다. 모의투자 값(KIS_PAPER)이 실전(KIS)에 섞이지 않게 키에 넣는다.

CREATE TABLE daily_bar (
    instrument_id  BIGINT         NOT NULL REFERENCES instrument (id),
    trade_date     DATE           NOT NULL,
    market         VARCHAR(2)     NOT NULL CHECK (market IN ('F', 'CM')),
    source         VARCHAR(16)    NOT NULL CHECK (source IN ('KIS', 'KIS_PAPER', 'SIMULATOR', 'REPLAY')),
    open_price     NUMERIC(12, 4) NOT NULL,
    high_price     NUMERIC(12, 4) NOT NULL,
    low_price      NUMERIC(12, 4) NOT NULL,
    close_price    NUMERIC(12, 4) NOT NULL,
    acc_volume     BIGINT         NOT NULL,
    acc_trade_value NUMERIC(20, 2) NOT NULL,
    mod_yn         VARCHAR(1),
    fetched_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date, market, source)
);

COMMENT ON TABLE daily_bar IS 'KIS 일봉(FHKIF03020100 output2). KIS 값을 그대로 두고 계산하지 않는다';
COMMENT ON COLUMN daily_bar.trade_date IS 'KIS 영업 일자(stck_bsop_date)';
COMMENT ON COLUMN daily_bar.market IS 'KIS 시장 분류 코드. F 지수선물(주간), CM 야간선물';
COMMENT ON COLUMN daily_bar.close_price IS '응답의 현재가(futs_prpr). 과거 일자는 그날 종가로 본다(가정)';
COMMENT ON COLUMN daily_bar.acc_volume IS '누적 거래량(acml_vol)';
COMMENT ON COLUMN daily_bar.acc_trade_value IS '누적 거래 대금(acml_tr_pbmn)';
COMMENT ON COLUMN daily_bar.mod_yn IS '변경 여부(mod_yn). 뜻은 문서에 없어 KIS 값만 보관한다';
COMMENT ON COLUMN daily_bar.fetched_at IS 'KIS 에서 받은 시각. 다시 받으면 갱신한다';

CREATE INDEX idx_daily_bar_date ON daily_bar (trade_date DESC);
