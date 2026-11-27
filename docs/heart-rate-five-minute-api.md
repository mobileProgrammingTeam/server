# 심박 상태 5분 보고 추가 명세 v3

적용 대상: worker-monitoring-events.patch(v2)를 이미 적용한 서버.
작업 사본: heart-rate-server. 원본 C:\Mobile_Project\server\server는 자동 수정하지 않았다.
이번 heart-rate-five-minute.patch는 현재 로컬 v2 코드에 추가 적용하는 패치다.

## 1. 주기와 역할

- 앱은 새 5분 측정 요약을 수락·판정할 때마다 기존 POST /api/worker/me/status를 호출한다.
- 평균 심박수 수치는 보내지 않고 확정한 NORMAL / DANGER만 보낸다.
- 이전 측정값을 타이머만으로 다시 보낸 뒤 measuredAt을 현재 시각으로 바꾸지 않는다.
- 서버는 요청을 수신하며 앱을 5분마다 호출하는 스케줄러는 아니다.
- 현재 앱의 전송 간격을 5분으로 사용한다. 10분 설정은 이번 5분·만료 계약과 맞지 않으므로 앱에서 5분으로 고정해야 한다.
- 심박 위험 사건은 기존 POST /api/worker/events에서만 등록한다.
- 상태 보고가 DANGER여도 서버에서 이벤트를 추가 생성하지 않는다.
- 정상 복귀 상태 보고는 과거 이벤트를 자동 처리 완료하지 않는다.

## 2. 앱의 확정 판정 기준

기존 심박 위험 이벤트와 같은 판정 결과를 사용한다. 서버는 숫자를 받지 않으므로 아래 규칙을 서버에서 다시 계산하지 않는다.

| 조건 | 보고 |
| --- | --- |
| 유효 심박 샘플이 3개 이상이고 평균이 50~120 bpm(경계 포함) | NORMAL |
| 평균이 50 미만 또는 120 초과인 유효 구간이 같은 방향으로 연속 두 번 | DANGER |
| 확정 위험 상태에서 새 유효 정상 구간 확인 | NORMAL로 복귀 |
| 처음 이상 구간 1회만 관측, 미측정·샘플 부족·만료·구간 연속성 불충족으로 확정 불가 | 심박 필드 둘 다 생략. 임의 NORMAL 보고 금지 |

두 구간은 5분 설정이 같고 겹치지 않으며, 이전 종료와 다음 시작의 간격이 1분 이하여야 한다.
5분 구간 2개이므로 초기 위험 확정에는 보통 10분이 필요하다. 5분마다 상태를 보고한다고 첫 이상 구간에서 즉시 위험 이벤트를 만들지 않는다.
이미 확정된 위험은 새 유효 정상 구간으로 복귀가 확인되기 전까지 정상으로 바꾸지 않는다.
새 판정이 불가능하면 기존 마지막 판정의 measuredAt을 갱신하지 않아야 한다.
50/120은 현재 앱의 개발용 고정 정책이며 진단 기준을 새로 정의한 것이 아니다.
앱에서는 상태 표시·상태 요청·위험 이벤트가 각각 다른 임계값을 사용하지 말고 동일한 판정 결과를 공유하도록 구현해야 한다.

## 3. 상태 요청

POST /api/worker/me/status
Authorization: Bearer <accessToken>
Content-Type: application/json

~~~json
{
  "monitoringStatus": "RUNNING",
  "reportedAt": "2026-11-27T03:10:02Z",
  "heartRateStatus": "NORMAL",
  "measuredAt": "2026-11-27T03:10:00Z",
  "devices": [
    {
      "type": "워치",
      "lastSeenAt": "2026-11-27T03:10:01Z"
    }
  ]
}
~~~

| 필드 | 필수 | 의미 |
| --- | --- | --- |
| monitoringStatus | 예 | 기존 RUNNING / STOPPED |
| reportedAt | 예 | 앱의 상태 보고 기준 시각 |
| devices | 아니요 | 기존 워치 수신 시각 보고 |
| heartRateStatus | 조건부 | NORMAL / DANGER. 앱의 확정 판정 결과 |
| measuredAt | 조건부 | 해당 판정의 근거가 된 최신 5분 측정 구간 종료 시각 |

심박 두 필드는 함께 보내거나 함께 생략한다. 둘 중 하나만 있으면 400.
둘 다 없거나 null이면 기존 심박 상태와 측정 시각을 유지한다. 기존 v2 앱 요청도 계속 허용한다.
같은 측정 시각·같은 상태의 재전송은 허용하지만 측정 시각은 그대로 둔다.
이전 측정 시각은 409 HEART_RATE_OUT_OF_ORDER, 같은 시각의 다른 상태는 409 HEART_RATE_CONFLICT.
측정 시각은 epoch 이후, 서버 시각보다 60초를 초과해 미래가 아니어야 하며 reportedAt보다 60초를 초과해 미래여도 거절한다.
reportedAt 및 기기 시각에 대한 기존 순서·충돌 검증은 유지한다.

## 4. 상태 응답

~~~json
{
  "monitoringStatus": "RUNNING",
  "reportedAt": "2026-11-27T03:10:02Z",
  "lastSeenAt": "2026-11-27T03:10:03Z",
  "watchLastSeenAt": "2026-11-27T03:10:01Z",
  "heartRateStatus": "NORMAL",
  "measuredAt": "2026-11-27T03:10:00Z",
  "heartRateDataStatus": "FRESH"
}
~~~

GET /api/worker/me와 기존 관리자 작업자 조회 WorkerInfo에도 heartRateStatus / measuredAt / heartRateDataStatus를 추가한다.
서버는 heartRateDataStatus를 다음처럼 계산한다.

| 상태 | 기준 |
| --- | --- |
| NOT_RECEIVED | 저장된 심박 판정이 아직 없음 |
| FRESH | 측정 구간 종료 이후 7분 이내 |
| STALE | 측정 구간 종료 이후 7분 초과 |

기본 전송 주기는 5분이다. 현재 앱의 지연 표시 정책과 맞춰 2분의 전달 여유를 더해 총 7분 이후 만료로 표시한다.
정확히 7분은 FRESH, 7분 초과부터 STALE이다.
유효한 상태 요청이나 위험 이벤트가 계속 도착해 lastSeenAt이 갱신돼도 measuredAt은 바뀌지 않는다.
STALE일 때 DB의 마지막 NORMAL/DANGER는 참고값으로 남고 화면에는 "만료"로 표시한다.
미수신을 NORMAL로 채우지 않는다.

## 5. 위험 이벤트는 기존 형식 유지

POST /api/worker/events

~~~json
{
  "time": "2026-11-27T03:10:02Z",
  "eventType": "ABNORMAL_VITAL",
  "alertSender": "ANDROID_HEART_RATE"
}
~~~

확정 위험 발생은 상태 DANGER 보고와 별도로 이 이벤트 요청을 한 번 보낸다.
상태 API는 이벤트를 만들지 않으므로 두 API의 역할이 중복되지 않는다.
기존 v2의 동일 심박 사건 재전송 중복 방지와 HTTP 201 EventInfo 응답을 유지한다.

## 6. 화면·SSE·DB

- 작업자 목록과 상세에 마지막 심박 상태와 측정 구간 종료 시각을 표시한다.
- 상태 보고 저장 후 기존 worker-status-changed SSE로 화면 일부를 재조회한다.
- 보고가 끊겼을 때도 만료 표시가 진행되도록 목록·상세 화면에서 30초마다 표시 HTML을 재조회한다.
- 이 30초는 화면 갱신 확인 주기이며 앱의 5분 전송 주기와 다르다.
- DB에 heart_rate_status와 heart_rate_measured_at을 nullable로 추가한다.
- 기존 감시 상태·워치 수신·서버 통신·위험 이벤트 이력은 유지한다.
- 기존 앱의 신규 상태 API 호출과 5분 판정 결과 연결은 앱 측 추가 작업이다. 서버 패치만으로 앱이 자동 전송하지 않는다.

## 7. 적용

현재 로컬에 v2 worker-monitoring-events.patch를 적용한 상태에서 이번 추가 패치만 적용한다.

~~~powershell
git apply --check --ignore-space-change .\heart-rate-five-minute.patch
git apply --ignore-space-change .\heart-rate-five-minute.patch
.\mvnw.cmd clean verify
docker compose up -d --build
~~~

v2를 적용하지 않은 master에는 이 추가 패치를 바로 적용하지 않는다.
이전의 독립 /me/heart-rate-status API를 되살리는 패치도 아니다.

## 8. 검증 상태

- 5분 간격의 NORMAL/DANGER 저장, 미수신, 두 필드 짝 검증, 역순·충돌, 상태 단독 이벤트 미생성, 정상 복귀 시 이력 유지, 만료 표시 회귀 테스트를 추가했다.
- 5분+2분 만료 경계 정책은 실제 production 클래스 대상으로 독립 Java 실행 검사를 수행한다.
- 전체 Spring/JPA/Thymeleaf 통합은 Maven 의존성과 Docker가 필요하다. 기존 환경 제한으로 전체 빌드·DB 통합 검증을 완료했다고 간주하지 않는다.
