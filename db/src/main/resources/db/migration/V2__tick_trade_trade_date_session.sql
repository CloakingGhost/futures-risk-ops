-- V2: tick_trade 에 거래일·세션을 더하고 같은 체결이 두 번 들어가지 않게 막는다.
--
-- H0IFCNT0 에는 체결 시각(HHMMSS)만 있고 날짜가 없다. 야간장은 자정을 넘기므로 '하루'를 따로 적어 둔다.
-- 지금 trade_date 는 임시 규칙(수신 시각의 한국 날짜, 자정 넘김 보정)으로 채운다. Day 2 세션 캘린더가 계산을 넘겨받는다.
-- 재접속·재배포·리플레이에서 같은 체결이 다시 오면 (종목, 거래일, 세션, 누적거래량) 이 같으므로 ON CONFLICT DO NOTHING 으로 버린다.
-- 세션을 키에 넣은 이유: 누적거래량이 주간·야간 세션마다 0부터 다시 시작하는지 아직 확인하지 못했다(가정).
--   다시 시작한다면 임시 규칙에서 같은 날짜의 주간·야간 체결이 서로 중복으로 잘못 버려진다.

ALTER TABLE tick_trade ADD COLUMN trade_date DATE;
ALTER TABLE tick_trade ADD COLUMN session VARCHAR(8);

-- V2 이전에 쌓인 행이 있으면 같은 임시 규칙으로 채운다.
UPDATE tick_trade
   SET trade_date = (traded_at AT TIME ZONE 'Asia/Seoul')::date,
       session = CASE
                     WHEN EXTRACT(HOUR FROM traded_at AT TIME ZONE 'Asia/Seoul') >= 18
                       OR EXTRACT(HOUR FROM traded_at AT TIME ZONE 'Asia/Seoul') < 6 THEN 'NIGHT'
                     ELSE 'DAY'
                 END;

-- 누적거래량이 비어 있으면 중복을 가릴 수 없으므로 그런 행은 남기지 않는다(V2 이전 행에만 해당).
DELETE FROM tick_trade WHERE acc_volume IS NULL;

ALTER TABLE tick_trade ALTER COLUMN trade_date SET NOT NULL;
ALTER TABLE tick_trade ALTER COLUMN session SET NOT NULL;
ALTER TABLE tick_trade ALTER COLUMN acc_volume SET NOT NULL;
ALTER TABLE tick_trade ADD CONSTRAINT tick_trade_session_check CHECK (session IN ('DAY', 'NIGHT'));

ALTER TABLE tick_trade DROP CONSTRAINT tick_trade_source_check;
ALTER TABLE tick_trade ADD CONSTRAINT tick_trade_source_check CHECK (source IN ('KIS', 'SIMULATOR', 'REPLAY'));

ALTER TABLE tick_trade
    ADD CONSTRAINT uq_tick_trade_dedup UNIQUE (instrument_id, trade_date, session, acc_volume);

COMMENT ON COLUMN tick_trade.trade_date IS '거래일. 지금은 임시 규칙(수신 한국 날짜), Day 2 세션 캘린더가 넘겨받는다';
COMMENT ON COLUMN tick_trade.session IS 'DAY 주간장, NIGHT 야간장(18:00~다음 날 06:00)';
COMMENT ON COLUMN tick_trade.source IS 'KIS 실데이터, SIMULATOR 합성, REPLAY 저장된 체결 재생';
