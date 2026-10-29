# simulator

KIS 실시간 체결(`H0IFCNT0`, 지수선물 체결) **형식**의 가짜 시세를 웹소켓으로 내보내는 서버입니다.
KIS 승인이나 장 시간을 기다리지 않고 `collector`·`risk` 를 개발하고, 끊김·지연·중단 같은 장애를 일부러 만들어 볼 때 씁니다.

> 모든 값은 합성입니다. 확인한 것은 **형식**(프레임 구조, 필드 이름·개수·순서, 구독/응답/PINGPONG JSON)뿐이고,
> 각 필드 값의 의미(부호, 단위, 코드값)는 가정입니다. 실제 시세와 같다고 믿고 리스크 계산을 검증하지 마세요.
> 키는 필요 없고, 실제 키를 넣지 않습니다(`CLAUDE.md` 규칙 2).

JDK 17 만으로 동작합니다(외부 라이브러리 없음). 웹소켓 서버(RFC 6455)는 직접 구현했습니다.

## 실행

```
./gradlew :simulator:run                                        # 기본 설정
./gradlew :simulator:run --args="--rate.ticks-per-second=20"    # 속도 변경
./gradlew :simulator:run --args="--help"                        # 옵션 목록
./gradlew :simulator:test                                       # 테스트
```

시작하면 `ws://127.0.0.1:18080/tryitout` 를 안내합니다(경로는 아무거나 받습니다). 종료는 Ctrl+C.

## 설정

우선순위: 기본값 < `--config=파일.properties` < 환경변수 `SIM_*` < 명령행 `--키=값`.
환경변수는 키를 대문자로 바꾸고 `.`·`-` 를 `_` 로 바꿔 `SIM_` 을 붙입니다(`rate.ticks-per-second` → `SIM_RATE_TICKS_PER_SECOND`).
모르는 키나 범위를 벗어난 값은 시작할 때 오류로 거절합니다(종료 코드 2). 예시 파일: `config/simulator.example.properties`.

| 키 | 기본값 | 설명 |
|---|---|---|
| `server.host` | 127.0.0.1 | bind address (127.0.0.1 = this PC only) |
| `server.port` | 18080 | listen port (0 = any free port) |
| `server.max-subscriptions` | 40 | max subscriptions per connection (KIS sample limit is 40) |
| `price.base` | 400.00 | starting price in index points |
| `price.prev-close` | (비움) | previous close; empty = price.base |
| `price.tick-size` | 0.05 | price tick |
| `price.volatility-ticks` | 1.5 | std-dev of each price step, in ticks |
| `price.multiplier` | 250000 | KRW per index point (for cumulative traded value) |
| `price.max-qty` | 10 | max quantity of one trade |
| `price.seed` | (비움) | random seed for a reproducible price path; empty = random |
| `rate.ticks-per-second` | 5 | trades per second per symbol (0.01 - 5000) |
| `rate.jitter` | 0.2 | random variation of the interval, 0 - 1 |
| `delay.fixed-ms` | 0 | constant delivery delay in ms |
| `delay.jitter-ms` | 0 | extra random delay in [0, value) ms |
| `delay.spike-probability` | 0 | chance (0 - 1) per trade of an extra delay spike |
| `delay.spike-ms` | 0 | size of a delay spike in ms |
| `disconnect.after-seconds` | 0 | server closes the connection this long after connect; 0 = off |
| `disconnect.probability-per-tick` | 0 | chance (0 - 1) per trade of closing the connection |
| `disconnect.abrupt` | false | true = reset TCP without a close frame; false = close code 1001 |
| `stall.after-seconds` | 0 | seconds after connect before sending silently stops |
| `stall.duration-seconds` | 0 | how long sending stays stopped; 0 = stall off |
| `stall.repeat` | false | repeat run-then-stall cycles |
| `pingpong.interval-seconds` | 30 | interval of PINGPONG messages; 0 = never |
| `resend.on-subscribe` | 0 | on subscribe, send again the last N trades of that symbol; 0 = off |

### 속도·끊김·지연 레시피

| 보고 싶은 상황 | 설정 |
|---|---|
| 빠른 장 (종목당 초당 100건) | `--rate.ticks-per-second=100` |
| 일정한 지연 150ms | `--delay.fixed-ms=150` |
| 들쭉날쭉한 지연 (50~250ms) + 가끔 2초 튐 | `--delay.fixed-ms=50 --delay.jitter-ms=200 --delay.spike-probability=0.01 --delay.spike-ms=2000` |
| 연결 후 30초마다 서버가 끊음 (정상 종료, 1001) | `--disconnect.after-seconds=30` |
| 비정상 끊김 (close 프레임 없이 TCP 리셋) | `--disconnect.after-seconds=30 --disconnect.abrupt=true` |
| 체결 1000건당 평균 1번 끊김 | `--disconnect.probability-per-tick=0.001` |
| 연결은 살아 있는데 10초 뒤 5초간 데이터가 안 옴 | `--stall.after-seconds=10 --stall.duration-seconds=5` |
| 위 중단을 반복 | `--stall.after-seconds=10 --stall.duration-seconds=5 --stall.repeat=true` |
| 재구독할 때 최근 체결 30건을 다시 보냄 (중복 적재 방지 확인) | `--disconnect.after-seconds=10 --resend.on-subscribe=30` |
| 같은 가격 경로 재현 | `--price.seed=42` |

- 지연은 체결 **전송**만 늦춥니다(체결 시각 필드는 그대로). 전송 순서는 항상 유지됩니다.
- 끊김·지연·중단의 시간 기준은 **연결마다 연결 시각**부터이고, 재접속하면 처음부터 다시 셉니다.
- 조용한 중단은 멈춘 동안의 체결을 **버립니다**(재전송 없음). 클라이언트는 연결이 살아 있어도 데이터가 끊긴 것을 스스로 알아채야 합니다.
- 시세는 종목 단위로 하나를 공유합니다. 같은 종목을 구독한 연결들은 같은 체결을 받습니다.
- 종목 피드는 처음 구독될 때 시작해 **구독자가 없어도 계속 체결을 만듭니다**(실제 시장처럼). 그래서 끊겼다 다시 붙으면 그사이 체결이 누적거래량의 빈틈으로 보입니다.
- `resend.on-subscribe` 는 구독 직후 그 종목의 최근 N건을 먼저 보냅니다. 이미 받은 체결이 다시 오는 상황을 만들 뿐이고, 실제 KIS 가 이렇게 하는지는 확인하지 않았습니다.

## 메시지 형식

구독 요청(클라이언트 → 서버). `tr_type` `"1"` 구독, `"2"` 해제. `approval_key` 는 아무 값이나 받고 검증하지 않습니다.

```json
{"header":{"approval_key":"dummy","custtype":"P","tr_type":"1","content-type":"utf-8"},
 "body":{"input":{"tr_id":"H0IFCNT0","tr_key":"101S12"}}}
```

응답(서버 → 클라이언트):

```json
{"header":{"tr_id":"H0IFCNT0","tr_key":"101S12","encrypt":"N"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS"}}
```

체결 프레임: `0|H0IFCNT0|001|` 뒤에 **50개 필드를 `^` 로 이은 문자열**. 필드 이름과 순서는 `kis/H0IFCNT0.java` 의 `Field` 입니다(공식 샘플 저장소의 컬럼 목록과 같은지 테스트로 비교).

```
0|H0IFCNT0|001|101S12^125934^0.05^2^0.01^400.05^400.05^400.05^400.05^3^3^300037500^399.98^0.27^0.02^...
```

(위 값은 `--price.seed=42` 로 만든 합성 예시입니다.)

`PINGPONG`: `pingpong.interval-seconds` 마다 서버가 `{"header":{"tr_id":"PINGPONG","datetime":"..."}}` 를 보내고, 클라이언트는 같은 내용을 되돌려 보내는 것이 KIS 방식입니다. 시뮬레이터는 받은 횟수만 셉니다.

오류(잘못된 JSON, 모르는 `tr_id`, 필수값 누락, 잘못된 `tr_type`, 구독 한도 초과)는 `rt_cd` `"1"` 과 시뮬레이터 자체 코드 `SIM0001` 로 응답합니다. KIS의 실제 오류 코드가 아닙니다.

## 구조

```
config/SimulatorConfig   설정 읽기·검증·옵션 목록
kis/H0IFCNT0, KisFrames   필드 정의, 프레임/응답/PINGPONG 만들기·파싱
market/PriceModel         종목별 가격 랜덤워크 (정수 틱, ±10% 한도, seed 가능)
ws/*                      웹소켓 서버, 연결, 최소 JSON
ClientSession             연결별 장애 주입 (지연 큐, 끊김, 중단, PINGPONG)
SimulatorServer, SimulatorMain
```

## 한계

- 한 종목당 가격 모델 하나이고 호가·미결제약정이 실제 시장처럼 움직이지 않습니다.
- 바이너리 프레임, 압축, TLS(`wss://`)는 지원하지 않습니다(`ws://` 만).
- 옵션 체결(`H0IOCNT0` 등) 등 다른 TR 은 아직 없습니다. 모르는 `tr_id` 는 오류로 응답합니다.
