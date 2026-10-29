-- V3: 중복 방지 키에 source 를 더한다.
--
-- V2 키 (instrument_id, trade_date, session, acc_volume) 에는 출처가 없어서, 같은 종목·같은 날의
-- 시뮬레이터·KIS(모의투자·실전)·리플레이 체결이 누적거래량만 같으면 서로를 중복으로 버렸다.
-- 시뮬레이터를 돌린 날 KIS 체결이 조용히 버려질 수 있었고, 이를 피하려면 truncate 로 실데이터까지 지워야 했다.
-- 이제 같은 출처 안에서만 중복을 가린다. 재접속·재배포로 같은 체결이 다시 오는 경우(같은 출처)는 그대로 막힌다.
-- 리플레이(REPLAY) 행은 원본 KIS 행과 따로 남는다(재생 결과를 원본과 비교할 수 있다).
-- 모의투자와 실전은 둘 다 KIS 라서 이 키로는 구분되지 않는다. 확인이 끝난 모의투자 데이터는 지금처럼 따로 지운다.

ALTER TABLE tick_trade DROP CONSTRAINT uq_tick_trade_dedup;
ALTER TABLE tick_trade
    ADD CONSTRAINT uq_tick_trade_dedup UNIQUE (instrument_id, trade_date, session, source, acc_volume);

COMMENT ON COLUMN tick_trade.trade_date IS '거래일. Day 2 부터 세션 캘린더(config/session-calendar.yaml)가 계산한다. 야간장은 다음 거래일(가정)';
