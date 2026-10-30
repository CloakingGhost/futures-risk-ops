-- V6: 체결 메시지(H0IFCNT0·H0MFCNT0)에 KIS 가 담아 보내는 베이시스·이론가·괴리율을 저장한다(Day 4).
-- 지수를 따로 받지 않고 이 값으로 베이시스 API 를 만든다. 코스피200 지수는 price - market_basis 로 거꾸로 구한다(가정).
-- 이미 쌓인 행에는 값이 없으므로 nullable 로 더한다. 지난 행은 채우지 않는다(원본 메시지를 저장하지 않았다).

ALTER TABLE tick_trade ADD COLUMN market_basis NUMERIC(12, 4);
ALTER TABLE tick_trade ADD COLUMN theoretical_price NUMERIC(12, 4);
ALTER TABLE tick_trade ADD COLUMN disparity_rate NUMERIC(10, 4);

COMMENT ON COLUMN tick_trade.market_basis IS '시장 베이시스(mrkt_basis). 선물가 - 코스피200 지수로 가정. V6 이전 행은 NULL';
COMMENT ON COLUMN tick_trade.theoretical_price IS 'HTS 이론가(hts_thpr). V6 이전 행은 NULL';
COMMENT ON COLUMN tick_trade.disparity_rate IS '괴리율 %(dprt). (선물가 - 이론가) / 이론가 x 100 으로 가정. V6 이전 행은 NULL';
