-- V5: Day 3. 종목 마스터 열을 더하고 호가(H0IFASP0)를 쌓을 tick_quote 를 만든다.
--
-- instrument: KIS 종목 마스터(fo_idx_code_mts.mst)에서 매일 장 전에 Upsert 한다(symbol 기준).
--   contract_month 는 결제월(YYYYMM), month_class 는 마스터의 월물구분코드(1 최근월물, 2 차근월물 ...)로 갱신한 날 기준 값이다.
--   expiry_date 는 V1 에 있던 열이며, 결제월 둘째 목요일(휴장이면 앞당김)로 collector 가 계산해 넣는다.
--   옵션은 v0.1 범위에서 뺐다. product_type 의 'OPTION' 값은 그대로 두고 채우지 않는다.
--
-- tick_quote: 주간장 5단계 호가. 호가에는 누적거래량 같은 중복 판별 키가 없어서 UNIQUE 를 두지 않는다
--   (재접속 때 같은 호가가 다시 오면 그대로 한 행 더 쌓인다).

ALTER TABLE instrument ADD COLUMN standard_code VARCHAR(12);
ALTER TABLE instrument ADD COLUMN contract_month CHAR(6);
ALTER TABLE instrument ADD COLUMN month_class SMALLINT;
ALTER TABLE instrument ADD COLUMN master_updated_at TIMESTAMPTZ;

COMMENT ON COLUMN instrument.standard_code IS '표준코드(KR4...). 마스터 파일 값';
COMMENT ON COLUMN instrument.contract_month IS '결제월 YYYYMM. 선물은 3·6·9·12월';
COMMENT ON COLUMN instrument.month_class IS '마스터 월물구분코드(1 최근월물, 2 차근월물 ...). master_updated_at 기준';
COMMENT ON COLUMN instrument.master_updated_at IS '마스터 파일로 마지막 갱신한 시각';

CREATE TABLE tick_quote (
    id             BIGSERIAL PRIMARY KEY,
    instrument_id  BIGINT           NOT NULL REFERENCES instrument (id),
    quoted_at      TIMESTAMPTZ      NOT NULL,
    received_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    trade_date     DATE             NOT NULL,
    session        VARCHAR(8)       NOT NULL CHECK (session IN ('DAY', 'NIGHT')),
    ask_prices     NUMERIC(12, 4)[] NOT NULL,
    bid_prices     NUMERIC(12, 4)[] NOT NULL,
    ask_qtys       INTEGER[]        NOT NULL,
    bid_qtys       INTEGER[]        NOT NULL,
    total_ask_qty  BIGINT,
    total_bid_qty  BIGINT,
    source         VARCHAR(16)      NOT NULL CHECK (source IN ('KIS', 'KIS_PAPER', 'SIMULATOR', 'REPLAY'))
);

COMMENT ON TABLE tick_quote IS '실시간 호가(H0IFASP0, 주간장). 배열은 1~5단계, 1이 최우선 호가';
COMMENT ON COLUMN tick_quote.quoted_at IS '호가 시각(bsop_hour 를 세션 캘린더로 날짜에 붙인 값)';

CREATE INDEX idx_tick_quote_instrument_time
    ON tick_quote (instrument_id, quoted_at DESC);
