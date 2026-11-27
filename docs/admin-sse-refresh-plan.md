# SSE 기반 관리자 웹 자동 갱신 작업 계획서

작성일: 2026-11-22 (Asia/Seoul)
검토 기준: 로컬 `dev-taegyuheo`, 커밋 `4905fa9`

관리자 웹에 SSE 변경 알림과 Thymeleaf 부분 갱신을 도입한다. 기존 Spring MVC·세션 인증·REST API·DB 스키마를 유지하고, 데이터 변경이 커밋된 뒤 관련 화면만 재조회한다. 구현 범위와 검증 기준은 아래와 같다.

## 1. 검토 결과와 적용 범위

원안의 `SseEmitter`·Spring ApplicationEvent·Thymeleaf Fragment·Vanilla JavaScript `EventSource` 조합을 채택한다. 구현 시 누락되기 쉬운 다음 항목을 보완한다.

| 원안 항목 | 보완 내용 |
| --- | --- |
| 커밋 이후 이벤트 전달 | ApplicationEvent는 트랜잭션 **안에서** 발행하고, AFTER_COMMIT 리스너에서 전송 작업을 예약한다. |
| 작업자 상태 변경 | `login()`의 잠금, `resetPassword()`·`changePassword()`의 잠금 해제도 검토한다. HTTP 실패와 DB 롤백을 구분한다. |
| 기존 조회 API 재사용 | 고정 예시 데이터를 반환하는 도메인 API와 실제 DB 조회를 구분한다. 화면 갱신은 기존 웹 컨트롤러의 조회를 재사용한다. |
| 작업자 변경의 영향 | 이름 변경·탈퇴 익명화는 이벤트 목록·상세·처리 기록의 작업자 이름에도 영향을 준다. |
| 연결 안정성 | 연결·큐 상한, 느린 연결 격리, 로그아웃·세션 만료, ASYNC 오류 처리를 검증한다. |
| UI 상태 보존 | 필터·페이지·모달·메모와 함께 DOM 교체 후 버튼 동작, 시간 표시, 보고서 검색·CSV도 검증한다. |
| 단일 인스턴스 가정 | 공유 DB를 쓰는 IntelliJ·Docker 앱도 서로 다른 JVM이다. 인메모리 알림은 다른 JVM의 변경을 전달하지 못한다. |

필수 범위는 대시보드, 긴급 알림, 이벤트 이력, 작업자 목록·상세, 처리 기록의 실제 DB 데이터 갱신이다. 처리 기록은 원안의 권장 항목에서 필수로 통일한다. Android 수정, 기존 API 응답 계약 변경, DB 스키마 변경, WebFlux·WebSocket·Redis 도입, 이벤트 영구 저장·재전송은 제외한다. 기기 통신·생체신호·구역·날씨 등 아직 연동되지 않은 데이터의 실시간화도 제외한다.

## 2. 현재 코드와 구현 근거

Java 경로의 기준은 `src/main/java/org/example/workersafety_server/`, 리소스 경로의 기준은 `src/main/resources/`다.

| 파일 또는 동작 | 확인된 구조 | 구현 방향 |
| --- | --- | --- |
| `pom.xml` | Spring Boot 4.1.2, Java 17, Spring MVC, JPA, Thymeleaf, Security, PostgreSQL, Testcontainers | 현재 의존성을 사용한다. |
| `config/SecurityConfig.java` | `/api/worker/**`는 무상태 Bearer 체인, `/admin/**`·`/api/admin/**`는 ADMIN 세션 체인 | SSE는 관리자 체인에 둔다. |
| `service/SafetyService.java` | 선언적 `@Transactional`과 `TransactionTemplate`을 함께 사용 | 실제 쓰기 트랜잭션 내부에서 변경 알림을 발행한다. |
| `controller/web/AdminWebController.java` | 대시보드·목록·상세·보고서를 실제 DB로 조회 | 동일 조회 조건으로 Fragment를 반환한다. |
| `controller/api/AdminDomainApiController.java` | `/api/admin/dashboard`, `/workers/status`, `/audit/events` 등은 `AdminUiFixtures` 사용 | 이번 갱신의 데이터 원본으로 사용하지 않는다. |
| `static/js/admin.js` | 작업자 생성 후 `workerList` 교체, 이벤트 페이지 5초 reload, 처리 성공 시 reload | 공통 갱신 경로로 모으고 검증 후 reload를 제거한다. |
| `templates/admin/workers.html` | `workerList`, `#worker-list`, `data-page` 존재 | 기존 Fragment 계약을 유지한다. |
| `templates/admin/reports.html` | 검색은 현재 페이지 행에 적용, JS가 초기 행 참조를 보관 | 교체된 행을 다시 선택해 필터·CSV를 적용한다. |
| `security/RequestLogFilter.java` | 최초 필터 호출의 finally에서 요청 로그 출력 | 기존 소요 시간을 SSE 연결 수명으로 해석하지 않는다. |

근거: [서비스](../src/main/java/org/example/workersafety_server/service/SafetyService.java), [웹 컨트롤러](../src/main/java/org/example/workersafety_server/controller/web/AdminWebController.java), [보안 설정](../src/main/java/org/example/workersafety_server/config/SecurityConfig.java), [관리자 JS](../src/main/resources/static/js/admin.js), [공유 DB 안내](shared-database.md).

## 3. 변경 알림과 트랜잭션

### 3.1 처리 순서

```text
기존 REST 요청
  → SafetyService의 쓰기 트랜잭션
  → DB 변경 + ApplicationEventPublisher.publishEvent(최소 정보)
  → COMMIT 성공
  → @TransactionalEventListener(AFTER_COMMIT)
  → 크기가 제한된 전송 실행기에 작업 제출
  → AdminEventStreamHub → 관리자 EventSource
  → 관련 Fragment GET → 해당 DOM 교체

ROLLBACK → 알림 전달 없음
```

리스너의 `fallbackExecution`은 기본값 false를 유지한다. 트랜잭션이 없는 호출에서 알림이 빠진다면 발행 위치를 수정한다. AFTER_COMMIT은 전송을 자동으로 비동기화하지 않으므로, 리스너 안에서 직접 네트워크 전송을 반복하지 않는다. [Spring 트랜잭션 이벤트 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html).

전송 실행기 제출 실패와 전송 오류는 격리해 이미 커밋된 쓰기 요청이 500으로 응답하지 않게 한다. 리스너는 DB를 추가로 변경하지 않는다. 이벤트에는 JPA 엔티티·지연 로딩 참조 대신 확정된 ID만 담는다. 알림 전달은 최선 노력 방식이며 커밋 직후 프로세스가 종료되면 유실될 수 있다. 재접속 시 현재 화면에 필요한 최신 DB 데이터를 재조회해 복구한다.

### 3.2 이벤트 계약과 발행 위치

SSE의 `event` 필드로 유형을 구분하고 `data`에는 아래 ID만 JSON으로 전달한다. 이름·성별·나이·처리 메모·비밀번호·토큰·세션 ID는 포함하지 않는다. `worker-status-changed`는 계정 잠금 상태를 뜻하며 기기·건강 상태를 뜻하지 않는다.

| SSE 이벤트 | payload | 발행 위치와 조건 |
| --- | --- | --- |
| `event-created` | `eventId`, `workerId` | `createEvent()` 저장 성공 후, 커밋 전 |
| `event-processed` | `eventId`, `workerId` | `processEvent()`의 조건부 UPDATE가 1건 성공한 경우만 |
| `worker-created` | `workerId` | `createWorker()`의 계정·프로필 저장 후 |
| `worker-updated` | `workerId` | `updateProfile()`의 실제 표시 정보 변경 시 |
| `worker-deleted` | `workerId` | `deleteAccount()`의 비활성화·익명화·토큰 폐기 후, TransactionTemplate 콜백 내부 |
| `worker-status-changed` | `workerId` | `login()`에서 잠금이 새로 설정될 때, 비밀번호 초기화·변경으로 기존 잠금이 해제될 때 |

```text
event: event-created
data: {"eventId":145,"workerId":12}

```

`login()`은 TransactionTemplate 콜백에서 실패 횟수·잠금을 커밋하고 콜백 밖에서 401 예외를 던질 수 있다. 따라서 **HTTP 401이어도 계정 잠금 알림이 필요할 수 있다.** 반대로 중복 이벤트 처리의 409, 입력 검증 실패, DB 롤백에서는 알림을 보내지 않는다. 단순 토큰 발급·로그아웃·실패 횟수 변경·잠금 변화 없는 비밀번호 변경에는 화면용 알림을 추가하지 않는다.

하나의 상태 전환에 알림을 중복 발행하지 않는다. 탈퇴는 `worker-deleted` 하나로 관련 화면을 무효화한다. 관리자가 직접 수행한 명령의 성공 후 갱신과 SSE 수신은 같은 JS 갱신 조정 함수를 호출해 요청을 병합한다.

## 4. SSE 연결과 서버 자원 관리

### 4.1 HTTP 계약

| 항목 | 계획 |
| --- | --- |
| 연결 | 신규 `GET /api/admin/stream`, `200 text/event-stream`, UTF-8 |
| 인증 | 동일 출처 관리자 세션 쿠키, `ROLE_ADMIN` 필수 |
| 최초 메시지 | Hub 등록 후 `connected` 이벤트와 빈 JSON, `retry: 3000` |
| heartbeat | 25초 간격의 SSE 주석. 화면 재조회는 유발하지 않음 |
| 캐시 | SSE·Fragment·세션 확인 응답에 `Cache-Control: no-store` |
| 연결 수명 | 초기 제안 5분. 종료 후 새 연결에서 세션 권한을 다시 검사 |
| 연결 상한 | 초기 제안 세션당 8개, 인스턴스 전체 256개. 초과는 응답 시작 전 429 |
| 세션 확인 | 신규 `GET /api/admin/session`: 정상 관리자 204, 미인증 401, 권한 부족 403, 본문에 계정 정보 없음 |
| 실패 응답 | 스트림 시작 전에는 기존 관리자 API의 JSON 오류 규칙 적용 |

상한과 수명은 구현 시 설정값으로 두고 운영 부하 검증으로 조정한다. 세션별 상한과 함께 동일 관리자 로그인 ID의 전체 연결 수도 추적한다. 동일 관리자·서로 다른 세션·여러 탭을 각각 별도 연결로 관리해 기존 연결을 덮어쓰지 않는다.

기본 `EventSource`는 임의 Authorization 헤더를 설정하는 API를 제공하지 않는다. 동일 출처 세션 쿠키를 사용하고 URL에 토큰을 넣지 않는다. 이벤트 저장소와 재전송 기능이 없으므로 SSE `id`·Last-Event-ID 기반 복구 계약은 만들지 않는다. [WHATWG EventSource 표준](https://html.spec.whatwg.org/multipage/server-sent-events.html#the-eventsource-interface).

### 4.2 연결 관리와 전송

`AdminEventStreamHub`는 연결별 ID, 관리자 식별자, 세션 연결 관계, 생성 시각, emitter를 thread-safe하게 관리한다. 완료·타임아웃·오류·초기 전송 실패·서버 종료에서 같은 정리 경로를 사용하고, 중복 정리도 안전해야 한다. 등록과 콜백 설정 사이의 종료 경쟁으로 누수가 생기지 않게 한다.

전송은 다음 조건을 충족한다.

- 요청 스레드와 heartbeat 스케줄러에서 직접 전체 연결에 쓰지 않는다. 별도 전송 실행기의 스레드 수와 대기 큐에 상한을 둔다.
- 연결별 전송을 직렬화하고 대기 알림 수에도 상한을 둔다. 한 연결의 쓰기 지연이 다른 연결을 순차적으로 막지 않게 한다.
- 큐 포화 시 무한 적재하거나 호출 스레드가 대신 쓰는 정책을 사용하지 않는다. 알림을 놓친 연결을 종료해 재접속·재조회로 복구하고 거절 횟수를 기록한다.
- 느린 연결의 쓰기를 중단·회수할 수 있도록 실제 Servlet 컨테이너의 쓰기 제한과 연결 종료를 검증한다. Future 취소만으로 소켓 쓰기가 종료된다고 가정하지 않는다.
- 완료 콜백에서 emitter, 연결별 대기 작업, 타이머, 세션 참조를 제거한다. heartbeat는 연결마다 새 스케줄러를 만들지 않고 공통 주기를 사용한다.

Spring MVC 스트리밍의 응답 쓰기는 블로킹이며, 클라이언트 이탈은 주기적인 쓰기 실패로 감지해야 한다. async 지원, emitter 타임아웃, 전송 실행기를 함께 점검한다. [Spring MVC 비동기 요청 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html).

### 4.3 세션과 비동기 보안

기존 관리자 보안 체인을 재사용하고 CSRF 검사를 전역 해제하지 않는다. 스트림 GET은 조회이며, 이벤트 처리 PATCH·비밀번호 초기화 POST·로그아웃 POST는 기존 CSRF를 유지한다. 작업자 Bearer 토큰만 제시한 SSE 요청은 관리자 인증으로 인정하지 않는다.

연결 시 인증만으로 로그아웃 뒤 기존 스트림이 자동 종료된다고 가정하지 않는다. 세션 무효화·만료 시 해당 세션의 emitter를 종료하는 연결을 추가하고, 다른 탭에서 로그아웃한 경우도 검증한다. heartbeat가 세션의 사용자 활동 시간을 임의로 연장하지 않게 한다. 최대 연결 수명은 재인증을 위한 추가 장치로 사용한다.

REQUEST·ASYNC·ERROR dispatch에서 권한과 예외 처리를 검사한다. ASYNC 전체를 무조건 허용하는 우회 규칙을 추가하지 않는다. 응답이 시작된 뒤에는 JSON 오류를 스트림에 덧붙이지 않고 연결을 종료한다. 현재 `ApiExceptionHandler`의 일반 예외 처리와 충돌하는 경우 SSE 경로에 한정해 조정한다. [Spring Security 요청 권한 문서](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

## 5. Fragment와 페이지 갱신

### 5.1 조회 경로

기존 화면 경로에 허용된 `fragment` 값을 추가하는 방식으로 통일한다. 임의 템플릿 이름을 입력받지 않는다. 아래 신규 이름은 구현할 계약이며, `workerList`와 이벤트 상세는 기존 계약이다.

| 경로 | Fragment와 교체 범위 | 알림에 따른 갱신 |
| --- | --- | --- |
| `/admin/dashboard?fragment=live` | `dashboardLive`: 통계·최근 이벤트·등록 작업자 영역 | 모든 작업자·이벤트 변경 |
| `/admin/alerts?fragment=list` | `alertList`: 미처리 건수·목록 | 이벤트 생성·처리, 작업자 이름 변경·탈퇴 |
| `/admin/events?fragment=list&...` | `eventList`: 목록·페이지 이동 | 이벤트 생성·처리, 작업자 이름 변경·탈퇴 |
| `/admin/workers?fragment=list&page=...` | 기존 `workerList`: 목록·페이지 이동 | 작업자 생성·변경·탈퇴·잠금 변화 |
| `/admin/workers/{workerId}?fragment=live&page=...` | `workerDetailLive`: 신원 표시·해당 작업자 이벤트·페이지 이동 | 해당 작업자 변경, 해당 작업자의 이벤트 생성·처리 |
| `/admin/reports?fragment=live&page=...` | `reportLive`: 통계·목록·페이지 이동 | 이벤트 생성·처리, 작업자 이름 변경·탈퇴 |
| `/admin/events/{eventId}/detail` | 기존 `eventDetail` | 열린 이벤트 또는 그 작업자의 이름·탈퇴 변경 |

대시보드의 정적 안내 영역 등은 필요에 따라 Fragment 밖에 둔다. 서로 떨어진 영역을 한 응답으로 갱신할 때는 반환 HTML에서 명시된 대상만 추출한다. `<main>` 전체나 모달·입력 폼을 일괄 교체하지 않는다. `GET /api/admin/dashboard`의 샘플 데이터를 실제 화면에 적용하지 않는다.

`processed`, `workerId`, `eventType`, `page`는 현재 페이지에 적용된 조회 조건을 그대로 전송한다. 아직 제출하지 않은 필터 입력은 DOM에 보존하고 자동 갱신 조건으로 사용하지 않는다. 페이지 크기는 현재 화면의 20건, 대시보드 최근 항목 등 기존 기준을 유지한다. 필터 결과 감소로 현재 페이지가 범위를 벗어날 때만 마지막 유효 페이지로 보정하고 URL도 맞춘다.

작업자 탈퇴는 행 삭제가 아니라 익명화와 비활성화다. 목록·이벤트에는 변경된 이름을 표시한다. `service.worker()`는 탈퇴 계정 상세 조회를 거절하므로 열린 작업자 상세가 404가 되면 이전 개인정보 영역을 지우고 탈퇴 안내와 목록 이동 링크를 표시한다. 이 목적을 위해 기존 REST 상세 조회 정책을 바꾸지 않는다.

### 5.2 클라이언트 갱신 조정

실제 갱신 대상이 있는 페이지에만 EventSource를 하나 생성한다. 공통 `admin.js`가 로그인 페이지에도 로드되므로, 로그인·미연동 화면에서는 연결하지 않도록 페이지 표식을 둔다.

1. 모든 리스너를 등록하고 서버의 `connected` 수신 후 최초 재조회를 수행한다. SSR 렌더링과 SSE 연결 사이의 변경도 이 조회로 반영한다.
2. 변경 이벤트는 관련 영역을 dirty로 표시하고 약 200ms 동안 병합한다. 연속 이벤트가 계속되어도 갱신이 무한 연기되지 않게 한다.
3. 영역별 요청은 한 번에 하나만 실행한다. 요청 중 알림이 오면 완료 후 한 번 더 조회해 마지막 변경을 놓치지 않는다.
4. 필터·페이지 이동으로 요청 조건이 바뀌면 이전 요청을 취소하거나 응답 세대를 검사해 오래된 응답의 DOM 적용을 막는다.
5. `connected` 재수신과 숨김 탭의 재활성화 시 현재 화면의 모든 동적 영역을 재조회한다. 전체 DB 데이터를 브라우저로 내려받지는 않는다.
6. `pagehide`에서 연결·타이머·fetch를 정리하고, 뒤로 가기 캐시로 복원된 `pageshow`에서는 중복 없이 다시 연결한다.

Fragment 조회는 `Accept: text/html`, 동일 출처 자격 증명을 사용한다. 로그인 리다이렉트·401·403, 응답 Content-Type, 예상 루트 요소를 검사한 뒤 DOM에 적용해 로그인 페이지나 오류 HTML이 목록에 삽입되지 않게 한다. 기존 JSON 전용 `request()`와 HTML 처리를 구분한다.

### 5.3 DOM 교체와 사용자 입력

`.open-event`, `.reset-password`, `.process-event-form`의 동작은 안정된 상위 요소에서 이벤트 위임하거나 중복 없는 재바인딩으로 유지한다. 현재 비밀번호 초기화 버튼은 최초 목록에만 리스너를 연결하므로 작업자 생성 후 목록 교체 문제도 함께 해결한다.

교체 영역의 `[data-time]`을 `Asia/Seoul`로 다시 표시한다. 보고서 검색값을 보존하고 새 행에 검색을 재적용한다. CSV 내보내기는 현재 DOM의 보이는 행을 읽고 빈 목록 이후 다시 데이터가 생기면 버튼 활성 상태도 갱신한다. 현재 페이지 검색·CSV 범위를 전체 데이터로 확장하지 않는다.

모달 바깥의 목록은 모달이 열린 동안에도 갱신한다. 열린 모달의 이벤트가 다른 관리자에 의해 처리되면 읽기 전용 상태·기록을 갱신하고 추가 처리를 막는다. 작성 중인 메모는 사용자 입력으로 따로 보존하고 서버의 처리 메모와 구분해 안내한다. 저장되지 않은 메모를 통째로 HTML 교체해 지우지 않는다. 처리 요청 중 409가 발생하면 실제 상세를 재조회하고 동일 정책으로 표시한다.

성공한 작업자 생성·이벤트 처리에서는 SSE 수신만 기다리지 않고 같은 갱신 함수를 직접 호출한다. 작업자 생성 결과의 임시 비밀번호 안내와 작성 중인 생성 폼은 목록 Fragment 밖에 유지한다. 명시적인 처리 성공으로 모달을 닫는 기존 흐름은 유지할 수 있다.

## 6. 장애 복구와 운영 조건

| 상황 | 처리 |
| --- | --- |
| 일시적 통신 단절·서버 재시작 | EventSource 기본 재접속을 사용하고 `connected` 후 재조회 |
| EventSource error | 상태 표시, 제한된 빈도로 `/api/admin/session` 확인. 이벤트만으로 HTTP 상태를 추측하지 않음 |
| 세션 확인 401 | EventSource 종료 후 로그인 이동 |
| 세션 확인 403 | 연결 종료 후 권한 안내. 로그인 반복 이동 금지 |
| 네트워크·5xx·429 | 인증 만료로 단정하지 않음. 브라우저 재연결 뒤 재생성이 필요하면 지연을 최대 30초까지 늘림 |
| 장기 연결 실패·EventSource 미지원 | 기존 화면을 유지하며 자동 갱신 중단 표시와 수동 부분 갱신 버튼 제공 |
| Fragment 실패 | 기존 표시와 입력을 유지하고 실패 상태 표시. 후속 알림·복구·수동 갱신으로 재시도 |
| 스트림 전송 실패·큐 포화 | 영향 연결 정리, 재연결 재조회로 복구 |

기본 재접속과 별도 재시도 루프가 경쟁하지 않게 한다. `readyState=CLOSED` 등 재생성이 필요한 경우에만 이전 객체를 닫고 하나를 만든다. EventSource의 error 콜백에서는 HTTP 상태나 Retry-After 헤더를 직접 읽을 수 없으므로 상태 확인은 별도 세션 요청으로 제한한다. 초기 버전의 대체 수단은 수동 부분 갱신으로 고정하며 정기 polling을 추가하지 않는다.

운영 경로에서 프록시 응답 버퍼링·캐싱을 끄고 idle timeout을 heartbeat보다 길게 설정한다. Nginx가 있다면 SSE 경로의 `proxy_buffering off` 또는 대응 헤더 적용을 검토한다. 압축·프록시 경유 때문에 이벤트가 모였다가 한꺼번에 도착하지 않는지 실제 배포 경로에서 확인한다. 현재 Compose에는 프록시 서비스가 없으므로 존재하지 않는 운영 구성을 가정해 추가하지 않는다.

활성 연결 수, 종료 이유, 전송 실패, 큐 거절, 대기 작업 수를 관찰 가능하게 한다. heartbeat마다 일반 로그를 남기거나 세션 ID·payload 전체·개인정보를 로그에 출력하지 않는다. 기존 요청 로그 외에 Hub의 연결 종료 정보를 사용해 수명과 누수를 검증한다.

### 단일 인스턴스 적용 조건

[현재 개발 구성](shared-database.md)은 IntelliJ 8080과 Docker 8081이 같은 PostgreSQL을 공유한다. SSE Hub와 ApplicationEvent는 JVM 내부에만 존재하므로 **8080에서 발생한 변경은 8081의 SSE 구독자에게 전달되지 않는다.** DB 공유나 세션 고정만으로 이 문제가 해결되지 않는다.

이번 구현의 실시간 보장 조건은 관련 변경 요청과 관리자 SSE 구독을 하나의 앱 인스턴스로 모으는 것이다. 두 서버가 동시에 쓰거나 DB를 직접 변경하는 상황은 즉시 알림 보장 범위 밖이며 재접속·탭 활성화·수동 재조회로만 반영된다. 이 구성이 실제 운영 요구와 맞지 않으면 인스턴스 간 이벤트 전달 설계를 별도 작업으로 선행해야 한다.

## 7. 변경 대상과 구현 순서

| 파일 | 작업 |
| --- | --- |
| 신규 `controller/api/AdminSseController.java` | stream·session GET, 응답 계약 |
| 신규 `service/AdminEventStreamHub.java` | 연결 등록·전송·정리·관찰 정보 |
| 신규 `service/event/AdminDataChangedEvent.java` | 최소 ID 기반 내부 이벤트 |
| 신규 이벤트 리스너·SSE 설정 파일 | AFTER_COMMIT, 제한된 실행기·heartbeat, 세션 종료 연동 |
| `service/SafetyService.java` | 실제 쓰기 트랜잭션 내부에 이벤트 발행 |
| `controller/web/AdminWebController.java` | 허용된 Fragment 분기, 기존 모델 조회 재사용 |
| `templates/admin/dashboard.html`, `alerts.html`, `events.html`, `workers.html`, `worker-detail.html`, `reports.html`, `fragments.html` | 페이지 표식·갱신 경계·상태 표시·메모 보존 |
| `static/js/admin.js` | 연결·복구·갱신 조정·DOM 재초기화, reload 제거 |
| `config/SecurityConfig.java`, `exception/ApiExceptionHandler.java` | 필요한 SSE·세션 종료·async 처리만 제한적으로 보완 |
| 관련 테스트·운영 안내 | 커밋·권한·스트리밍·UI·단일 인스턴스 조건 검증 |

구현은 다음 순서로 진행한다. 각 단계의 검증을 통과한 뒤 다음 단계로 넘어간다.

1. 기존 테스트를 실행해 기준 결과와 실패 원인을 기록한다. 단일 인스턴스 실행 경로·설정 기본값을 고정한다.
2. 이벤트 발행·AFTER_COMMIT 전달을 구현한다. 실제 커밋과 롤백, 로그인 401 잠금, 처리 409 무알림을 검증한다.
3. SSE Controller·Hub·세션 종료·실행기를 구현한다. 권한, 동시 연결, 실패 정리, 포화·느린 연결을 검증한다.
4. 이벤트 이력·긴급 알림의 Fragment와 공통 JS를 먼저 연결한다. 두 관리자에서 생성·처리·메모 보존·재접속을 검증한다.
5. 대시보드·작업자 목록·상세·처리 기록을 연결한다. 이름 변경·탈퇴 전파와 검색·페이지·CSV·버튼 동작을 검증한다.
6. 자동 갱신·수동 복구 검증 후 5초 전체 reload와 처리 성공 reload를 제거한다. Docker·운영 경로를 확인하고 전체 회귀 검사를 수행한다.

## 8. 검증 계획과 완료 조건

### 8.1 서버 검증

기존 JUnit·MockMvc·PostgreSQL Testcontainers를 재사용한다. 무한 스트림을 최종 응답처럼 기다리는 테스트를 만들지 않는다. MockMvc는 async 시작·헤더·보안을 확인하고 Hub 테스트는 유한 대기와 명시적 정리를 사용한다. 실제 flush·소켓 종료·세션 만료는 임의 포트의 내장 서버와 실제 HTTP 클라이언트로 별도 검증한다.

| 번호 | 검증 항목 | 성공 기준 |
| --- | --- | --- |
| S-01 | 관리자 연결·connected·heartbeat | 스트림 형식·헤더 정상, heartbeat가 목록 조회를 유발하지 않음 |
| S-02 | 익명·WORKER·Bearer-only 접근 | 관리자 세션 없이 스트림을 열 수 없음, 기존 401·403 규칙 유지 |
| S-03 | 이벤트 생성·처리·작업자 생성·수정·탈퇴 | 실제 커밋 후 계약된 ID의 알림 전달 |
| S-04 | 롤백·중복 처리·검증 실패·무변경 | 알림 미전달, 실패 시 기존 데이터 보존 |
| S-05 | 로그인 잠금·초기화 잠금 해제 | 401 뒤 커밋된 잠금은 전달, 상태 전환당 1회 |
| S-06 | 두 관리자·동일 관리자 여러 탭 | 모든 정상 연결 수신, 한 탭 종료가 다른 연결을 지우지 않음 |
| S-07 | 종료·timeout·IO 오류·초기 실패·종료 경쟁 | Hub 연결과 작업 자원이 원래 수준으로 회수됨 |
| S-08 | 느린 연결·연속 이벤트·상한·큐 포화 | 자원 상한 유지, 쓰기 요청 결과와 정상 연결 전송이 실패 연결에 묶이지 않음 |
| S-09 | 로그아웃·세션 무효화·만료·ASYNC/ERROR | 기존 스트림 종료, 이후 신규 알림 없음, 커밋된 응답에 JSON 오류를 덧붙이지 않음 |
| S-10 | Fragment 조회·세션 확인·캐시 | 최신 DB 값, 조건 보존, no-store, 허용하지 않은 Fragment 거절 |

S-03·S-04는 `TransactionTemplate` 또는 명시적 테스트 트랜잭션 종료로 실제 커밋·롤백을 발생시킨다. 테스트 전체의 자동 롤백으로 AFTER_COMMIT 리스너가 실행되지 않는 상황을 성공으로 오인하지 않는다. 전송 실행기 거절과 emitter 실패를 주입해 저장 성공 응답도 확인한다. 기존 테스트 중 `SafetyService`를 직접 생성하는 곳은 생성자 변경 시 함께 맞춘다.

### 8.2 브라우저와 운영 검증

- 두 관리자 세션에서 작업자 생성·프로필 수정·로그인 잠금·잠금 해제·안전 이벤트 생성·처리·탈퇴를 실행하고 관련 화면 전파를 확인한다. 정상 연결·로컬 검증 환경에서 커밋 후 2초 이내 반영을 초기 목표로 삼고 실제 지연을 기록한다.
- 이벤트 필터·2페이지 이상 목록·보고서 검색·열린 모달·작성 중 메모·작업자 생성 폼을 유지한 채 다른 탭에서 변경한다. 오래된 fetch 응답을 지연시켜 최신 상태를 덮어쓰지 않는지 확인한다.
- 동일 이벤트를 두 관리자가 동시에 처리한다. 최초 성공 결과를 보존하고 다른 화면은 실제 완료 기록을 표시하며 작성 중이던 메모를 잃지 않아야 한다.
- Fragment를 여러 차례 교체한 뒤 상세 버튼·비밀번호 초기화·처리 폼·시간 표시·CSV가 중복 실행 없이 동작하는지 확인한다.
- 오프라인·서버 재시작·연결 사이 변경·백그라운드 복귀·뒤로 가기·다른 탭 로그아웃·세션 만료에서 재연결과 최신 조회를 확인한다. 401과 네트워크 실패를 구분해야 한다.
- Docker에서 30분 이상 유지해 여러 번의 연결 수명 갱신을 확인한다. 탭 반복 개폐와 느린 클라이언트로 연결·큐·메모리가 지속 증가하지 않는지 확인한다.
- HTTP/1.1 다중 탭의 브라우저 연결 제한도 확인한다. 서버의 연결 상한만으로 브라우저 제한이 해결된다고 가정하지 않는다.
- IntelliJ·Docker 동시 실행에서는 인스턴스 간 즉시 전파가 지원되지 않음을 재현하고 실행 안내에 명시한다.

구현 후 기존 로그인·Bearer 인증·CSRF·탈퇴 이력·토큰 폐기·동시 이벤트 처리 회귀 테스트와 신규 테스트를 실행한다. 프로젝트 루트에서 Windows 기준 `./mvnw.cmd clean verify`를 사용한다. Maven Wrapper·JDK·Docker 준비가 필요하며 테스트는 공유 개발 DB 대신 Testcontainers DB를 사용한다.

### 8.3 완료 체크리스트

- [ ] 최소 정보의 변경 알림이 실제 DB 커밋 이후에만 전달된다.
- [ ] HTTP 실패 후 커밋되는 계정 잠금과 탈퇴에 따른 이름 변경까지 화면에 반영된다.
- [ ] 갱신 대상 여섯 화면에서 전체 페이지 reload 없이 최신 DB 값을 표시한다.
- [ ] 필터·페이지·입력·모달·메모를 보존하고 교체 이후 버튼·시간·보고서 기능이 정상이다.
- [ ] 최초 연결·재접속·탭 복귀에서 누락된 변경을 재조회로 복구한다.
- [ ] 세션 종료 후 기존 SSE가 닫히고 기존 인증·CSRF·REST 계약이 유지된다.
- [ ] 연결·작업 큐 상한과 느린 클라이언트 정리가 실제 HTTP 환경에서 확인된다.
- [ ] 기존 5초 reload와 처리 성공 reload를 제거하고 수동 부분 갱신 수단을 제공한다.
- [ ] 단일 인스턴스 적용 조건, 설정값, 프록시 확인 결과, 회귀 테스트 결과를 기록한다.

## 9. 참고 자료

- [Spring MVC 비동기 요청과 SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [Spring 트랜잭션 이벤트](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Spring Security 요청 권한](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [WHATWG SSE와 EventSource](https://html.spec.whatwg.org/multipage/server-sent-events.html)
