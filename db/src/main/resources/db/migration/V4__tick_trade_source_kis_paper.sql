-- V4: tick_trade.source 에 KIS_PAPER(모의투자)를 더한다.
--
-- 지금까지 모의투자(kis-paper 프로필)와 실전(kis 프로필) 체결이 둘 다 'KIS' 로 적혀 섞이면 나눌 수 없었다.
-- 이제 kis-paper 프로필은 'KIS_PAPER' 로 적는다. 중복 방지 키(V3)에 source 가 있으므로 두 출처는 서로를 막지 않는다.
-- V4 이전에 쌓인 'KIS' 행이 모의투자에서 온 것인지는 DB 만으로 알 수 없어서 바꾸지 않는다.
-- 모두 모의투자에서 왔다고 확실하면 직접 바꾼다: UPDATE tick_trade SET source = 'KIS_PAPER' WHERE source = 'KIS';

ALTER TABLE tick_trade DROP CONSTRAINT tick_trade_source_check;
ALTER TABLE tick_trade ADD CONSTRAINT tick_trade_source_check
    CHECK (source IN ('KIS', 'KIS_PAPER', 'SIMULATOR', 'REPLAY'));

COMMENT ON COLUMN tick_trade.source IS 'KIS 실전, KIS_PAPER 모의투자, SIMULATOR 합성, REPLAY 저장된 체결 재생';
