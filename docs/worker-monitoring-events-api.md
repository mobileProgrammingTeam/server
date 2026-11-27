# v3 추가 안내

심박 정상·위험의 5분 상태 보고를 추가했다. 최신 요청·만료 규칙은 heart-rate-five-minute-api.md를 따른다.
아래는 v2의 기준 문서이며, 심박 상태를 받지 않는다는 설명은 v3에서 변경되었다.

# 작업자 감시 상태·심박 위험 이벤트 API v2

기준: server/master fb77e30. 구현 위치: heart-rate-server 작업 사본.
원본 C:\Mobile_Project\server\server에는 적용하지 않았다.
이 문서와 worker-monitoring-events.patch는 이전 heart-rate-status.patch를 대체한다.
두 패치를 함께 적용하지 않는다. 이전 패치가 미적용인 master 기준의 전체 패치다.

## 1. 역할 구분

| 목적 | 경로 | 서버 처리 |
| --- | --- | --- |
| 감시 실행 상태·워치 관측 보고 | POST /api/worker/me/status | 상태만 저장, 위험 이벤트 생성 안 함 |
| 심박 위험 발생 | POST /api/worker/events | ABNORMAL_VITAL 이벤트 저장 |
| 낙상·위험 비콘 | 기존 POST /api/worker/events | 기존 계약 유지 |

평균 심박수, 온도, 산소포화도, 현장 구역, NFC는 받지 않는다.
심박 정상·위험의 주기적 업로드와 정상 복귀 보고는 요구하지 않는다.
이전 작업 사본의 /api/worker/me/heart-rate-status와 HeartRateStatus 저장 필드는 제거했다.

## 2. 공통

Authorization: Bearer <accessToken>
Content-Type: application/json

작업자 ID는 인증 토큰으로 식별하고 이름은 DB에서 조회한다.
앱은 workerId·workerName·eventId·receivedAt·관리자 처리 정보를 보내지 않는다.
앱 시각은 UTC ISO 8601 문자열이며 서버는 밀리초 정밀도로 저장한다.

## 3. 감시 상태 요청

POST /api/worker/me/status

~~~json
{
  "monitoringStatus": "RUNNING",
  "reportedAt": "2026-11-27T03:10:00Z",
  "devices": [
    {
      "type": "워치",
      "lastSeenAt": "2026-11-27T03:09:58Z"
    }
  ]
}
~~~

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| monitoringStatus | RUNNING / STOPPED | 예 | 감시 실행·중지 여부. 작업자의 정상·위험과 별개 |
| reportedAt | datetime | 예 | 앱이 이번 감시 상태를 관측한 시각. 재시도 때 동일하게 유지 |
| devices | array | 아니요 | 현재 지원하는 워치 관측 0~1건 |
| devices[].type | string | 항목 포함 시 | 현재는 정확히 "워치"만 허용 |
| devices[].lastSeenAt | datetime | 항목 포함 시 | 휴대폰이 새로운 워치 요약을 수락한 시각 |

reportedAt은 늦게 도착한 과거 RUNNING 보고가 최신 STOPPED를 덮어쓰는 것을 막기 위한 필드다.
devices는 생략하거나 []로 보내면 기존 워치 수신 시각을 유지한다.
기존보다 오래된 워치 수신 시각으로 되돌리지 않는다.
휴대폰이 서버와 통신한 시각은 서버가 기록하므로 휴대폰 항목을 요청에 넣지 않는다.
새 요약이 없는데 연결 확인·화면 새로고침·재전송 시각으로 워치 lastSeenAt을 바꾸지 않는다.

RUNNING은 실제 필요한 감시 서비스가 실행 중임을 앱에서 확인한 뒤 전송한다.
워치가 연결되어 있다는 뜻도, 심박수가 정상이라는 뜻도 아니다.
미수신은 서버에서 null로 유지하며 화면에 "미수신"으로 표시한다.
상태 변경·로그인 후 실제 감시 시작·앱의 최신 상태 보고 시 이 API를 사용할 수 있다.
전송 주기를 강제하는 heartbeat 스케줄러나 온라인·오프라인 판정은 이번 구현에 추가하지 않았다.

## 4. 감시 상태 응답

HTTP 200, Cache-Control: no-store

~~~json
{
  "monitoringStatus": "RUNNING",
  "reportedAt": "2026-11-27T03:10:00Z",
  "lastSeenAt": "2026-11-27T03:10:01.125Z",
  "watchLastSeenAt": "2026-11-27T03:09:58Z"
}
~~~

lastSeenAt은 상태·이벤트 API를 정상 수신한 서버 시각이다. 모든 API 호출을 추적하는 전역 접속 시각은 아니다.
같은 상태 보고의 유효한 재시도도 서버 통신 시각을 갱신하지만 reportedAt과 워치 관측 시각은 새로 만들지 않는다.
GET /api/worker/me 및 관리자 작업자 조회 WorkerInfo 응답에도 아래 nullable 필드를 추가했다.
- monitoringStatus: RUNNING / STOPPED / null
- statusReportedAt: 마지막 앱 상태 보고 시각 또는 null
- lastSeenAt: 상태·이벤트 API의 마지막 서버 수신 시각 또는 null
- watchLastSeenAt: 앱이 보고한 최신 워치 요약 수신 시각 또는 null

화면에는 마지막으로 알려진 상태를 표시한다. 오래된 RUNNING을 현재 접속이나 감시 지속 보장으로 해석하지 않는다.

## 5. 심박 위험 요청

기존 POST /api/worker/events를 그대로 사용한다.

~~~json
{
  "time": "2026-11-27T03:10:00Z",
  "eventType": "ABNORMAL_VITAL",
  "alertSender": "ANDROID_HEART_RATE"
}
~~~

time은 앱이 심박 위험을 확정한 시각이다.
위험 감지 기준은 앱에서 판정한다. 서버는 수치를 받거나 임계값을 다시 계산하지 않는다.
정상일 때는 심박 이벤트를 보내지 않는다. 상태 API로 심박 위험을 보내지도 않는다.
현재 앱이 사용하는 요청 형태와 동일하므로 심박 이벤트 경로를 새 API로 교체할 필요가 없다.

## 6. 심박 위험 응답·재전송

기존 HTTP 201 및 EventInfo 응답을 유지한다.

~~~json
{
  "eventId": 123,
  "workerId": 101,
  "workerName": "김민준",
  "time": "2026-11-27T03:10:00Z",
  "receivedAt": "2026-11-27T03:10:01.125Z",
  "eventType": "ABNORMAL_VITAL",
  "alertSender": "ANDROID_HEART_RATE",
  "processedAt": null,
  "processedBy": null,
  "adminMemo": null
}
~~~

정확한 심박 이벤트 재전송은 다음 조합으로 중복을 판별한다.
- 토큰의 작업자 ID
- eventType=ABNORMAL_VITAL
- alertSender=ANDROID_HEART_RATE (공백 제거 후 비교)
- 동일한 발생 시각 time (밀리초 기준)

계정 행 잠금 안에서 조회·저장하므로 같은 이벤트의 동시 요청도 중복 생성하지 않는다.
중복이면 기존 이벤트의 ID·원래 수신 시각·관리자 처리 결과를 그대로 반환한다.
중복 반환도 기존 앱의 성공 검사와 맞추기 위해 HTTP 201을 유지한다.
새 이벤트일 때만 event-created SSE를 발행한다. 중복은 알림 이력을 새로 만들지 않는다.
다른 발생 시각의 심박 위험은 별도 사건으로 저장한다. 위험 상태가 이어지는 동안 앱이 발생 시각을 바꿔 반복 전송하면 별도 사건이 된다.
통신 재시도에서 time을 현재 시각으로 바꾸지 않는다.
낙상·비콘 또는 다른 alertSender 조합에 이 중복 방지를 일반화하지 않았다.
실제 자동 재시도 스케줄러는 앱에 추가하지 않았다.

감시 중지·재시작이나 관리자 이벤트 처리 완료는 다른 상태를 자동으로 바꾸지 않는다.
과거 심박 이벤트는 현재 심박 위험 상태로 간주하지 않는다.

## 7. 오류·순서

| 경우 | 결과 |
| --- | --- |
| 토큰 누락·만료·폐기 | 기존 401 인증 오류 |
| enum·필수 필드·기기 타입·날짜 형식 오류 | 400 INVALID_REQUEST |
| 상태·워치 시각 또는 심박 위험 시각이 epoch 이하/서버보다 60초 초과 미래 | 400 INVALID_REQUEST |
| 워치 시각이 reportedAt보다 60초 초과 미래 | 400 INVALID_REQUEST |
| 기존보다 과거 reportedAt | 409 WORKER_STATUS_OUT_OF_ORDER |
| 같은 reportedAt에 다른 monitoringStatus | 409 WORKER_STATUS_CONFLICT |
| 같은 reportedAt·같은 monitoringStatus | 200, 동일 상태 유지 |

reportedAt이 동일한 재시도에서 워치 정보가 더 최신이면 워치 시각만 전진할 수 있다.
지원 필드 외 값을 정상 수신·저장한다고 가정하지 않는다.
기존 심박 상태 수신 API는 제공하지 않으므로 그 경로를 호출하지 않는다.

## 8. 관리자 화면·SSE

- 작업자 목록: 마지막 감시 실행 상태, 워치 마지막 수신, 서버 마지막 통신.
- 작업자 상세: 마지막 보고 상태·시각, 워치 요약 수신 시각, 실제 위험 이벤트.
- 심박 위험: 기존 긴급 알림·이벤트 이력·상세에서 "심박수 위험"으로 표시.
- 심박 위험 열의 이벤트 확인 링크는 기존 ABNORMAL_VITAL 이력 필터를 사용한다.
- 현장 구역 UI는 유지하고 실제 구역 데이터는 연결하지 않는다.
- 저장 확정 후 기존 worker-status-changed / event-created 알림으로 Thymeleaf HTML 일부를 재조회한다.
- 심박 정상·위험 배지, 수치 그래프, 알림 소리·브라우저 팝업은 추가하지 않았다.

## 9. 저장·적용·검증

worker_profiles에 monitoring_status / status_reported_at / last_seen_at / watch_last_seen_at 열을 추가한다.
기존 계정·이벤트를 삭제하지 않는다. 심박 상태 열은 새로 만들지 않는다.
아직 원본 서버에 어떤 패치도 적용하지 않은 상태에서 최신 v2 패치만 적용한다.

~~~powershell
git status
git apply --check --ignore-space-change "C:\경로\worker-monitoring-events.patch"
git apply --ignore-space-change "C:\경로\worker-monitoring-events.patch"
.\mvnw.cmd clean verify
~~~

적용 후 서버 재빌드·재기동이 필요하다.
감시 상태 전송은 새 API이므로 앱 측 호출 추가가 필요하고, 기존 심박 이벤트 전송은 그대로 사용한다.
신규 통합 테스트는 상태 보고가 이벤트를 만들지 않는지, 역순·충돌·기기 시각·인증 검증, 작업자 격리, HTML 표시, 심박 이벤트 재전송·처리 상태 유지·동시 중복 방지를 검사한다.
테스트는 Testcontainers PostgreSQL을 사용하므로 Docker가 필요하다.
작성 환경에서는 Spring Boot 4.1.2 의존성 캐시 부재와 Docker 미실행으로 빌드·DB 통합 실행을 완료하지 못했다.
Java 구문 검사와 원본 파일 복사본 대상 패치 적용 검사는 별도로 수행한다.
