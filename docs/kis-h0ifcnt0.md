# KIS H0IFCNT0 (지수선물 실시간체결가) 필드

웹소켓 텍스트 프레임은 `암호화여부|TR_ID|데이터건수|데이터` 네 부분이다. 데이터는 `^` 로 이어진 필드이고, 건수가 3이면 필드가 50 x 3 = 150개 이어진다.

```
0|H0IFCNT0|001|A01612^090000^0.35^...   (합성 예시)
```

- 필드 이름과 순서: 공식 샘플 `koreainvestment/open-trading-api` 의 `domestic_futureoption_functions_ws.py` (`index_futures_realtime_conclusion` columns 50개). 시뮬레이터 `H0IFCNT0.Field` 와 collector `H0ifcnt0Parser` 가 이 표를 따르고, `H0ifcnt0ParserTest` 가 둘의 순번이 같은지 확인한다.
- 값의 의미(단위, 부호, 코드값)는 **가정**이다. 실제 수신으로 확인하면 이 문서에 적는다. 확인에 쓴 실제 메시지는 저장소에 넣지 않는다.
- PINGPONG 과 구독 응답은 `|` 프레임이 아니라 JSON 이다. PINGPONG 은 받은 텍스트를 그대로 돌려보낸다.

## 필드 (순번은 0부터)

"collector" 열은 `tick_trade` 에 쓰는 필드다.

| 순번 | 이름 | 설명 | collector |
|---|---|---|---|
| 0 | `futs_shrn_iscd` | 선물 단축 종목코드 | symbol |
| 1 | `bsop_hour` | 영업 시간(HHMMSS) | bsop_hour → traded_at, trade_date, session |
| 2 | `futs_prdy_vrss` | 선물 전일 대비 |  |
| 3 | `prdy_vrss_sign` | 전일 대비 부호(1상한 2상승 3보합 4하한 5하락) |  |
| 4 | `futs_prdy_ctrt` | 선물 전일 대비율 |  |
| 5 | `futs_prpr` | 선물 현재가 | price |
| 6 | `futs_oprc` | 선물 시가 |  |
| 7 | `futs_hgpr` | 선물 최고가 |  |
| 8 | `futs_lwpr` | 선물 최저가 |  |
| 9 | `last_cnqn` | 최종 거래량(이번 체결 수량) | qty |
| 10 | `acml_vol` | 누적 거래량 | acc_volume (중복 키) |
| 11 | `acml_tr_pbmn` | 누적 거래 대금 |  |
| 12 | `hts_thpr` | HTS 이론가 | theoretical_price (V6) |
| 13 | `mrkt_basis` | 시장 베이시스 | market_basis (V6) |
| 14 | `dprt` | 괴리율 | disparity_rate (V6) |
| 15 | `nmsc_fctn_stpl_prc` | 근월물 약정가 |  |
| 16 | `fmsc_fctn_stpl_prc` | 원월물 약정가 |  |
| 17 | `spead_prc` | 스프레드 가격 |  |
| 18 | `hts_otst_stpl_qty` | 미결제 약정 수량 | open_interest |
| 19 | `otst_stpl_qty_icdc` | 미결제 약정 수량 증감 |  |
| 20 | `oprc_hour` | 시가 시간 |  |
| 21 | `oprc_vrss_prpr_sign` | 시가 대비 현재가 부호 |  |
| 22 | `oprc_vrss_nmix_prpr` | 시가 대비 지수 현재가 |  |
| 23 | `hgpr_hour` | 최고가 시간 |  |
| 24 | `hgpr_vrss_prpr_sign` | 최고가 대비 현재가 부호 |  |
| 25 | `hgpr_vrss_nmix_prpr` | 최고가 대비 지수 현재가 |  |
| 26 | `lwpr_hour` | 최저가 시간 |  |
| 27 | `lwpr_vrss_prpr_sign` | 최저가 대비 현재가 부호 |  |
| 28 | `lwpr_vrss_nmix_prpr` | 최저가 대비 지수 현재가 |  |
| 29 | `shnu_rate` | 매수 비율 |  |
| 30 | `cttr` | 체결강도 |  |
| 31 | `esdg` | 괴리도 |  |
| 32 | `otst_stpl_rgbf_qty_icdc` | 미결제 약정 직전 수량 증감 |  |
| 33 | `thpr_basis` | 이론 베이시스 |  |
| 34 | `futs_askp1` | 선물 매도호가1 |  |
| 35 | `futs_bidp1` | 선물 매수호가1 |  |
| 36 | `askp_rsqn1` | 매도호가 잔량1 |  |
| 37 | `bidp_rsqn1` | 매수호가 잔량1 |  |
| 38 | `seln_cntg_csnu` | 매도 체결 건수 |  |
| 39 | `shnu_cntg_csnu` | 매수 체결 건수 |  |
| 40 | `ntby_cntg_csnu` | 순매수 체결 건수 |  |
| 41 | `seln_cntg_smtn` | 총 매도 수량 |  |
| 42 | `shnu_cntg_smtn` | 총 매수 수량 |  |
| 43 | `total_askp_rsqn` | 총 매도호가 잔량 |  |
| 44 | `total_bidp_rsqn` | 총 매수호가 잔량 |  |
| 45 | `prdy_vol_vrss_acml_vol_rate` | 전일 거래량 대비 누적 거래량 비율 |  |
| 46 | `dscs_bltr_acml_qty` | 동시호가 등 누적 수량 |  |
| 47 | `dynm_mxpr` | 실시간 상한가 |  |
| 48 | `dynm_llam` | 실시간 하한가 |  |
| 49 | `dynm_prc_limt_yn` | 실시간 가격제한 구분 |  |

## 확인한 것 (2026-10-28, 모의투자 주간장)

- 모의투자 서버가 주간장에 `H0IFCNT0` 체결을 내보낸다. 구독 키(`tr_key`) `A01612` 형식이 웹소켓에서도 통한다(35분 동안 12,665건).
- 누적거래량과 체결 수량의 관계: 빈틈은 있었지만 음수(누적거래량이 수량보다 덜 늘어남)는 없었다. 체결이 몰린 초에 1~4계약씩 빈틈이 생겼다(합 95계약, 0.59%). 묶음 전송인지 수신 손실인지는 확정하지 못했다.
- 야간장 체결은 이 TR 이 아니라 `H0MFCNT0`(KRX야간선물 실시간종목체결 [실시간-064])로 온다(공식 샘플 `examples_llm/domestic_futureoption/krx_ngt_futures_ccnl`). 그날 야간장에 `H0IFCNT0` 로는 체결이 오지 않았다.

## 확인한 것 (2026-10-29, 종목 마스터·공식 샘플)

- 야간 체결 `H0MFCNT0` 의 필드는 공식 샘플(`krx_ngt_futures_ccnl.py`) columns 49개다. 위 표에서 46번 `dscs_bltr_acml_qty` 하나만 빠지고 순서는 같다. collector 가 쓰는 0~18번은 위치가 같다.
- 야간 종목코드: KIS 야간 마스터(`fo_cme_code.mst`)의 코스피200 선물 코드가 주간 마스터(`fo_idx_code_mts.mst`)와 같은 `A01612` 형식이었다. KIS 문서·샘플의 `101V06`, `101W09`, `101W9000` 은 예전 코드 체계로 본다(가정, 야간 실접속으로 최종 확인).
- 주간 마스터의 코스피200 선물 7종목(`A01612` ~ `A01812`)은 모두 `A01` + 연도 끝자리 + 월 두 자리 규칙과 맞았다(`FuturesCode`).
- KIS 문서는 `H0MFCNT0`, `H0IFASP0` 를 '모의투자 미지원'이라고 적었다. `H0IFCNT0` 도 같은 표시였지만 모의투자 주간장에서 왔다.

## 확인할 것

- 실전에서도 `A01612` 형식이 통하는지
- 야간장 `H0MFCNT0` 이 `A01612` 형식으로 실제 체결을 보내는지, 모의투자에서 제공하는지
- 호가 `H0IFASP0` 가 모의투자에서 오는지
- 연결 중 빈틈이 KIS 묶음 전송인지(같은 시각 체결 여러 건을 한 건으로 보내는지)
- 누적거래량(`acml_vol`)이 주간·야간 세션마다 0부터 다시 시작하는지. 그래서 중복 키에 세션을 넣었다(Flyway V2). V3 부터 키에 출처(source)도 들어간다. 누락 감지(`GapDetector`)도 세션이 바뀌면 기준을 새로 잡는다
- 야간장 체결의 거래일. 지금은 '다음 거래일'로 가정했다(`config/session-calendar.yaml` 의 `night_trade_date`). KRX 야간 파생상품시장 업무규정으로 확인한 뒤 이 값만 바꾸고, 쌓인 행은 `--collector.backfill-trade-dates=true` 로 다시 계산한다
- 재접속해 다시 구독했을 때 KIS 가 직전 체결을 다시 보내는지(시뮬레이터 `resend.on-subscribe` 는 이 경우를 흉내 낸다)
- 한 연결의 실시간 구독 한도(샘플 40건, 블로그 41건)
- `mrkt_basis` 가 '선물 현재가 - 코스피200 지수'인지, `dprt` 가 '(선물가 - 이론가) / 이론가 x 100' 인지. 장중에 HTS 화면의 베이시스·괴리율과 한 번 맞춰 본다. 값이 비었거나 숫자가 아니면 null 로 두고 체결은 그대로 받는다
- 접속키(approval_key) 유효기간. 지금은 24시간으로 가정하고 만료 1시간 전부터 접속할 때 새로 받는다(`KisApprovalKeyClient`)
- 잘못되거나 만료된 접속키로 구독하면 KIS 가 어떤 응답을 주는지. 지금은 msg1 에 `approval` 이 들어 있으면 접속키 거절로 보고 키를 버린 뒤 다시 붙는다(`RealtimeClient.approvalRejected`)
