-- V1: Day 0 기본 테이블 3개.
-- 번호가 붙은 마이그레이션 파일은 고치지 않는다. 바꿀 때는 새 번호(V2, V3 ...) 파일을 더한다.
-- source 열은 합성(시뮬레이터) 데이터와 실제 KIS 데이터를 섞지 않으려고 둔다.

CREATE TABLE instrument (
    id            BIGSERIAL PRIMARY KEY,
    symbol        VARCHAR(16)   NOT NULL UNIQUE,
    name          VARCHAR(100),
    product_type  VARCHAR(16)   NOT NULL CHECK (product_type IN ('FUTURES', 'OPTION')),
    underlying    VARCHAR(32),
    expiry_date   DATE,
    multiplier    NUMERIC(14, 2),
    tick_size     NUMERIC(10, 4),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE instrument IS '종목 마스터. symbol 은 KIS 종목 단축코드';
COMMENT ON COLUMN instrument.multiplier IS '거래승수. 공식 자료로 확인한 뒤 채운다';

CREATE TABLE futures_quote_snapshot (
    id             BIGSERIAL PRIMARY KEY,
    instrument_id  BIGINT         NOT NULL REFERENCES instrument (id),
    captured_at    TIMESTAMPTZ    NOT NULL,
    last_price     NUMERIC(12, 4) NOT NULL,
    prev_close     NUMERIC(12, 4),
    open_price     NUMERIC(12, 4),
    high_price     NUMERIC(12, 4),
    low_price      NUMERIC(12, 4),
    acc_volume     BIGINT,
    open_interest  BIGINT,
    basis          NUMERIC(12, 4),
    source         VARCHAR(16)    NOT NULL CHECK (source IN ('KIS', 'SIMULATOR'))
);

COMMENT ON TABLE futures_quote_snapshot IS '현재가 스냅샷(REST 조회 결과)';

CREATE INDEX idx_snapshot_instrument_time
    ON futures_quote_snapshot (instrument_id, captured_at DESC);

CREATE TABLE tick_trade (
    id             BIGSERIAL PRIMARY KEY,
    instrument_id  BIGINT         NOT NULL REFERENCES instrument (id),
    traded_at      TIMESTAMPTZ    NOT NULL,
    received_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    price          NUMERIC(12, 4) NOT NULL,
    qty            INTEGER        NOT NULL CHECK (qty >= 0),
    acc_volume     BIGINT,
    open_interest  BIGINT,
    source         VARCHAR(16)    NOT NULL CHECK (source IN ('KIS', 'SIMULATOR'))
);

COMMENT ON TABLE tick_trade IS '실시간 체결(H0IFCNT0). traded_at 은 체결 시각, received_at 은 수신 시각';

CREATE INDEX idx_tick_trade_instrument_time
    ON tick_trade (instrument_id, traded_at DESC);
