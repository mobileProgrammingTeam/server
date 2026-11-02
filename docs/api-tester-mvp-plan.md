# Python API 테스터 MVP 구현 계획

## 목적

Python 파일 하나를 실행하면 현재 서버의 모든 REST API를 정해진 순서로 호출한다. 성공한 요청은 `메서드 경로 OK`만 출력하고, 실패한 요청은 `메서드 경로 KO`와 보낸 요청·받은 응답을 상세히 출력한다.

각 API의 정상 동작을 한 번 이상 확인하는 도구로 만든다. 메뉴, 오류 입력별 반복 테스트, 테스트 프레임워크, GUI, 파일 보고서는 추가하지 않는다. 이 문서는 계획이며 실행 코드는 아직 작성하지 않는다.

## 1. 대상과 구성

현재 REST API는 `/health` 1개, 작업자 API 6개, 관리자 API 7개로 총 14개다. 같은 경로라도 메서드가 다르면 별도 API로 센다.

관리자 인증에 필요한 `GET /admin/login`, `POST /admin/login`, `POST /admin/logout`과 CSRF 토큰을 가져올 `GET /admin/events`도 실행한다. 관리자 HTML 화면은 REST API 대상이 아니지만, CSRF 토큰을 얻기 위한 요청만 추가한다.

```text
tools/api_tester.py                 # 실행 코드 한 파일
docs/api-tester-mvp-plan.md         # 이 계획서
```

- 외부 라이브러리는 `httpx` 하나만 사용한다.
- JSON, UTC 시각, 비밀번호 입력, HTML 토큰 추출은 Python 표준 라이브러리로 처리한다.
- API 경로와 순서는 코드에 직접 작성한다. 자동 API 검색이나 설정 파일은 만들지 않는다.
- 서버 코드는 변경하지 않으며 사용자가 서버를 미리 실행한다.

실행 예시:

```powershell
python -m pip install httpx
python tools/api_tester.py
```

## 2. 입력값

시작할 때 세 가지 값만 입력받고, 이후에는 추가 입력 없이 순서대로 실행한다.

| 입력 | 처리 |
| --- | --- |
| 서버 주소 | 엔터를 누르면 `http://localhost:8080`. HTTP·HTTPS 주소 사용 |
| 관리자 ID | 기존 관리자 계정 ID |
| 관리자 비밀번호 | `getpass`로 입력하여 화면에 표시하지 않음 |

비밀번호의 공백을 임의로 제거하지 않는다. 서버의 `.env`를 읽거나 자격증명을 파일에 저장하지 않는다.

테스트 데이터는 코드에서 정하고, 응답으로 받은 ID·임시 비밀번호·토큰만 메모리에 보관한다.

| 데이터 | 사용할 값 |
| --- | --- |
| 작업자 이름 | `API 테스트 작업자` |
| 성별·나이 | `U`, `25` |
| 프로필 변경 | 이름을 `API 테스트 작업자 수정`으로 변경 |
| 이벤트 시각 | 실행 시점의 UTC, ISO 8601 밀리초 형식 |
| 이벤트 유형 | `FALL_DETECTED` |
| 발신 주체 | `API_TESTER` |
| 변경할 비밀번호 | 실행 중 생성한 12~64자의 ASCII 임의 문자열 |
| 목록 쿼리 | `page=0`, `size=20`. 이벤트는 생성한 `workerId`로 필터 |

기존 작업자의 비밀번호나 계정 상태를 변경하지 않고, 이번 실행에서 새로 만든 작업자만 사용한다.

## 3. 실행 순서

로그아웃·비밀번호 변경·초기화로 토큰이 무효화되면 명시적으로 재로그인한다. 탈퇴는 작업자 요청의 마지막에 실행한다.

| 순서 | 메서드·경로 | 기대 상태 | 내용 |
| --- | --- | --- | --- |
| 1 | `GET /health` | 200 | 서버와 DB 상태 확인 |
| 2 | `GET /admin/login` | 200 | 로그인 폼의 CSRF 토큰과 세션 쿠키 확보 |
| 3 | `POST /admin/login` | 302 | 관리자 ID·비밀번호·CSRF를 폼으로 전송 |
| 4 | `GET /admin/events` | 200 | 로그인 확인 및 HTML에서 CSRF 토큰·헤더명 확보 |
| 5 | `POST /api/admin/workers` | 201 | 테스트 작업자 생성, ID·임시 비밀번호 저장 |
| 6 | `GET /api/admin/workers` | 200 | 작업자 목록 조회 |
| 7 | `GET /api/admin/workers/{workerId}` | 200 | 생성한 작업자 상세 조회 |
| 8 | `POST /api/worker/auth/login` | 200 | 임시 비밀번호로 로그인, 토큰 저장 |
| 9 | `PATCH /api/worker/me` | 200 | 테스트 작업자의 이름 변경 |
| 10 | `POST /api/worker/events` | 201 | 이벤트 등록, 이벤트 ID 저장 |
| 11 | `GET /api/admin/events` | 200 | 테스트 작업자의 이벤트 목록 조회 |
| 12 | `GET /api/admin/events/{eventId}` | 200 | 등록한 이벤트 상세 조회 |
| 13 | `PATCH /api/admin/events/{eventId}/process` | 200 | 이벤트 처리 완료 |
| 14 | `GET /api/admin/events/{eventId}` | 200 | 재조회하여 메모가 저장됐는지 확인 |
| 15 | `POST /api/worker/auth/logout` | 204 | 현재 작업자 토큰 폐기 |
| 16 | `POST /api/worker/auth/login` | 200 | 기존 임시 비밀번호로 재로그인 |
| 17 | `PATCH /api/worker/me/password` | 204 | 비밀번호 변경, 메모리의 현재 토큰 제거 |
| 18 | `POST /api/worker/auth/login` | 200 | 변경한 비밀번호로 재로그인 |
| 19 | `POST /api/admin/workers/{workerId}/reset-password` | 200 | 초기화된 비밀번호 저장, 작업자 토큰 제거 |
| 20 | `POST /api/worker/auth/login` | 200 | 초기화된 임시 비밀번호로 재로그인 |
| 21 | `DELETE /api/worker/me` | 204 | 현재 비밀번호를 JSON 본문으로 보내 탈퇴 |
| 22 | `POST /admin/logout` | 302 | 관리자 세션 종료 |

총 22회 요청으로 14개 REST API와 인증에 필요한 경로를 모두 사용한다. 작업자 로그인은 토큰 무효화에 맞춰 반복 호출한다.

## 4. 인증과 요청 처리

- 관리자와 작업자는 별도 `httpx.Client`를 사용한다.
- 관리자 Client는 쿠키를 유지하고 관리자 POST·PATCH 요청에 CSRF 헤더를 붙인다.
- 관리자 로그인은 JSON이 아닌 폼 데이터로 보내며 필드명은 `adminId`, `password`, 로그인 폼의 CSRF 필드명을 사용한다.
- 로그인 후 대시보드에서 CSRF 값을 다시 읽는다. 로그인 이전 토큰을 계속 사용하지 않는다.
- 작업자 요청에는 `Authorization: Bearer <accessToken>`을 붙인다.
- 리다이렉트는 자동 추적하지 않는다. 로그인은 `/admin/dashboard`, 로그아웃은 `/admin/login?logout`으로 향하는지 확인한다.
- 요청 타임아웃은 10초로 고정하고 자동 재시도는 하지 않는다.

## 5. 성공·실패 검증

정확한 상태 코드와 아래 최소 응답 검증이 모두 맞으면 `OK`다. 상태 코드가 맞아도 본문이 잘못되면 `KO`다.

| 대상 | 최소 검증 |
| --- | --- |
| 상태 확인 | JSON의 `status`가 `UP` |
| 로그인 페이지 | HTML에서 로그인 CSRF 필드·값 추출 가능 |
| 관리자 로그인·로그아웃 | `302` 및 예상 `Location` |
| 대시보드 | HTML에서 CSRF 토큰·헤더명 추출 가능 |
| 작업자 생성 | 양의 정수 `workerId`, 비어 있지 않은 `temporaryPassword` |
| 비밀번호 초기화 | 생성한 작업자 ID, 비어 있지 않은 `temporaryPassword` |
| 작업자 로그인 | 비어 있지 않은 `accessToken`, `tokenType=Bearer`, 비어 있지 않은 `expiresAt` |
| 작업자 상세·프로필 수정 | 생성한 ID, 예상 이름·성별·나이, `enabled=true` |
| 작업자 목록 | `items`가 배열이며 페이지 필드 존재. 새 작업자가 첫 페이지에 있다는 가정은 하지 않음 |
| 이벤트 등록·상세 | 양의 이벤트 ID, 생성한 작업자 ID, 보낸 유형·발신 주체와 일치 |
| 이벤트 목록 | `items`에 이번 실행에서 생성한 이벤트 ID 포함 |
| 이벤트 처리 | `adminMemo`와 함께 처리 요청, 동일 이벤트 ID, 비어 있지 않은 `processedAt`, 양의 정수 `processedBy` |
| 처리 메모 확인 | 처리 후 이벤트 상세를 다시 조회해 `adminMemo`가 유지되는지 확인 |
| 작업자 로그아웃·비밀번호 변경·탈퇴 | `204`이고 본문이 비어 있음 |

페이지 정렬, 입력 경계값, 토큰 만료, 이전 토큰의 실제 무효화 여부까지 반복 검증하는 기능은 추가하지 않는다.

## 6. 출력 형식

### 성공

HTTP 메서드, 실제 경로, `OK`만 한 줄로 출력한다. 성공 응답 본문, 상태 코드, 진행 메시지, 최종 통계는 출력하지 않는다. 시작 시 입력 프롬프트는 표시한다.

```text
GET /health OK
GET /admin/login OK
POST /admin/login OK
GET /admin/events OK
POST /api/admin/workers OK
GET /api/admin/workers OK
GET /api/admin/workers/42 OK
POST /api/worker/auth/login OK
PATCH /api/worker/me OK
POST /api/worker/events OK
GET /api/admin/events OK
GET /api/admin/events/87 OK
PATCH /api/admin/events/87/process OK
GET /api/admin/events/87 OK
```

경로의 ID는 템플릿 대신 실제 사용한 값으로 표시한다. 성공 행에서는 쿼리를 생략하고, 실패 상세에는 포함한다.

### 실패

첫 줄에 `KO`를 표시하고, 바로 아래에 실패 이유와 실제 요청·응답을 상세히 표시한다.

```text
POST /api/worker/events KO
이유: 기대 HTTP 상태 201 / 실제 400

[보낸 요청]
URL: http://localhost:8080/api/worker/events
메서드: POST
헤더:
  Content-Type: application/json
  Authorization: Bearer [숨김]
본문:
{
  "time": "2026-11-02T01:00:00.000Z",
  "eventType": "FALL_DETECTED",
  "alertSender": "API_TESTER"
}

[받은 응답]
HTTP 상태: 400
헤더:
  content-type: application/json
본문:
{
  "code": "INVALID_REQUEST",
  "message": "요청 내용을 확인하세요."
}
```

- 상세 정보에는 URL의 쿼리, 전체 헤더, JSON 또는 폼 본문을 포함한다. 준비한 입력값만이 아니라 실제 전송한 요청을 기준으로 표시한다.
- JSON은 들여쓰기하고, HTML 등 JSON이 아닌 본문은 텍스트로 표시한다. 빈 본문은 `(빈 본문)`으로 표시한다.
- 상태 코드가 맞아도 응답 내용 검증이 실패하면 기대값·실제값을 이유에 표시한다.
- 비밀번호·임시 비밀번호·Bearer 토큰·Cookie·Set-Cookie·CSRF 값은 `[숨김]`으로 대체한다. HTML 안의 CSRF 값도 숨긴다. 표시용 복사본만 수정하며 실제 요청은 바꾸지 않는다.
- 연결 실패·타임아웃도 `KO`로 표시한다. 응답에는 `수신 없음`, 이유에는 예외 종류와 내용을 표시한다. 타임아웃 시 서버 반영 여부는 불명확하므로 쓰기 요청을 자동 재전송하지 않는다.

## 7. 실패 시 동작과 테스트 데이터

MVP는 첫 `KO`에서 종료한다. 앞 단계에서 얻은 ID·토큰을 후속 요청이 사용하므로, 실패 뒤에 연쇄 오류를 출력하지 않는 단순한 구조로 한다. 실행하지 않은 API를 성공으로 표시하지 않는다.

- 전부 성공하면 종료 코드 `0`, 실패하면 `1`이다.
- 중간 실패 시 자동 정리·중간 재개는 구현하지 않는다. 다시 실행하면 새로운 테스트 작업자를 생성한다.
- 성공 시 테스트 작업자를 탈퇴시키지만, 현재 서버는 논리 삭제이므로 작업자 레코드와 이벤트 이력은 남는다.
- 개발·테스트 서버를 대상으로 사용하고 DB 파일을 직접 조작하지 않는다.

## 8. 코드 구성과 구현 순서

클래스나 범용 실행 엔진 대신 작은 함수와 순차 코드로 작성한다.

| 함수 | 역할 |
| --- | --- |
| `main()` | 입력, 22회 요청 순서, ID·비밀번호·토큰 관리 |
| `request_and_check()` | 요청 전송, 상태·최소 내용 검증, OK·KO 출력 및 실패 종료 |
| `read_csrf()` | HTML의 CSRF 필드 또는 meta 정보 추출 |
| `print_failure()` | 비밀값을 숨기고 실제 요청·응답 상세 출력 |

1. **공통 요청·출력 처리** → `/health` 성공은 한 줄, 서버 연결 실패는 `KO`와 상세 정보가 출력되는지 확인한다.
2. **관리자 인증·작업자 생성** → CSRF·쿠키를 사용하고 생성한 ID로 작업자 상세를 조회하는지 확인한다.
3. **나머지 API 추가** → 테스트 서버에서 22회 요청이 모두 성공하고 14개 REST API가 빠짐없이 실행되는지 확인한다.
4. **실패 출력 확인** → 틀린 관리자 비밀번호 또는 확인용 잘못된 요청으로 실패시켜, 요청·응답·이유가 보이고 비밀값은 숨겨지는지 확인한다.

별도 테스트 프레임워크는 도입하지 않는다. 정상 실행과 실패 실행의 출력·종료 코드로 확인한다.

## 완료 기준

- 시작 시 서버 주소·관리자 ID·비밀번호만 입력한다.
- 현재 14개 REST API를 포함한 22회 요청을 의존관계에 맞춰 자동 순차 실행한다.
- 성공은 `메서드 경로 OK`만 출력한다.
- 실패는 `메서드 경로 KO`와 보낸 요청·받은 응답·실패 이유를 상세히 표시한다.
- 첫 실패에서 종료하고 자동 재시도하지 않는다.
- 실행 코드는 Python 파일 하나, 외부 라이브러리는 `httpx` 하나다.
