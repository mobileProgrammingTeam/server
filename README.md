# server

작업자 안전 이벤트를 저장하고 관리자 웹에서 처리하는 Spring Boot 서버입니다. Android 앱은 REST API를 이용하며 앱 자체는 이 저장소에 포함되지 않습니다.

## 구성

- Java 17, Maven, Spring Boot 4.1.2, Spring Security, Thymeleaf, Spring Data JPA, SQLite
- 작업자는 불투명 Bearer 토큰을 사용하고 관리자는 세션과 CSRF 토큰을 사용합니다.
- SQLite의 `accounts`, `worker_profiles`, `safety_events`, `mobile_tokens` 테이블과 관계는 [구현 지시서의 ERD](docs/implementation-instructions.final.md) 5.2절을 참고하세요. 시간은 UTC epoch milliseconds로 저장합니다.
- 위험 이벤트 화면은 5초마다 폴링합니다. WebSocket, 위치·날씨 수집, 즉시 알림 전달은 구현하지 않습니다.

## 환경변수

| 이름 | 설명 | 기본값 |
| --- | --- | --- |
| `ADMIN_LOGIN_ID` | 최초 관리자 로그인 ID | 관리자 부재 시 필수 |
| `ADMIN_INITIAL_PASSWORD` | 최초 관리자 비밀번호 | 관리자 부재 시 필수 |
| `SQLITE_DB_PATH` | SQLite 파일 경로 | 로컬 `./data/workersafety.db`, Docker `/data/workersafety.db` |
| `SERVER_PORT` | HTTP 서버 포트 | `8080` |
| `MOBILE_TOKEN_TTL` | 모바일 토큰 유효 기간 | `PT12H` |
| `SPRING_PROFILES_ACTIVE` | 실행 프로필 | 로컬 `local`, Docker `prod` |

관리자 비밀번호는 12~64자이고 UTF-8 72바이트 이하여야 합니다. 기존 관리자 계정이 있으면 초기 자격증명으로 덮어쓰지 않습니다. 기본 `local` 프로필은 프로젝트 작업 디렉터리의 `.env`를 UTF-8 Java Properties 형식으로 읽습니다. OS·IntelliJ 환경변수와 명령행 설정은 파일 값보다 우선합니다. `.env`는 선택 사항이며, 파일이 없으면 기존 환경변수 주입 방식으로 실행할 수 있습니다. 최초 관리자 계정이 없을 때 두 관리자 값이 모두 필요합니다.

`.env`는 `KEY=value` 형식으로 작성하고 값에 따옴표를 붙이거나 `export`를 쓰지 마세요. Java Properties의 이스케이프 규칙이 적용되므로 백슬래시가 포함된 값은 주의하고 Windows 경로에는 `/` 사용을 권장합니다. Docker Compose도 `.env`를 읽지만 보간 문법이 다를 수 있으니 특수문자 비밀번호는 Compose 동작을 별도로 확인하세요. `.env`와 실제 비밀값은 Git에 저장하지 않습니다.

## 로컬 실행

JDK 17과 프로젝트 루트 작업 디렉터리를 사용합니다. `.env.example`을 프로젝트 루트의 `.env`로 복사하고 `ADMIN_LOGIN_ID`, `ADMIN_INITIAL_PASSWORD`에 최초 관리자 정보를 입력한 뒤 IntelliJ에서 실행하면 됩니다. IntelliJ의 Run Configuration에서 Working directory가 프로젝트 루트인지 확인하세요. 파일을 추가하거나 수정한 뒤 서버를 재시작해야 설정이 다시 읽힙니다. `.env` 없이 실행하려면 다음과 같이 환경변수로 전달할 수 있습니다.

```powershell
$env:ADMIN_LOGIN_ID = Read-Host '관리자 로그인 ID'
$adminSecret = Read-Host '최초 관리자 비밀번호' -AsSecureString
$env:ADMIN_INITIAL_PASSWORD = [System.Net.NetworkCredential]::new('', $adminSecret).Password
.\mvnw.cmd spring-boot:run
```

관리자 화면은 `http://localhost:8080/admin/login`, 상태 확인은 `GET /health`입니다. 최초 계정 생성 후에는 DB를 유지한 채 서버를 재시작해도 비밀번호가 변경되지 않습니다.

## API

모바일 API는 로그인 후 `Authorization: Bearer <accessToken>`을 전송합니다. 관리자 API는 관리자 폼 로그인 세션을 사용하며 POST·PATCH 요청에 CSRF 토큰을 포함해야 합니다. 관리자 화면은 필요한 CSRF 토큰을 자동 전송합니다. 비밀번호 변경·초기화·탈퇴 후 모든 기존 모바일 토큰은 무효화됩니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/worker/auth/login` | `workerId`, `password`로 로그인; `accessToken`, `tokenType`, `expiresAt` 반환 |
| POST | `/api/worker/auth/logout` | 현재 토큰 폐기 |
| POST | `/api/worker/events` | `time`, `eventType`, `alertSender`로 이벤트 등록 |
| PATCH | `/api/worker/me` | `workerName`, `sex`, `age` 중 전달한 항목 수정 |
| PATCH | `/api/worker/me/password` | `currentPassword`, `newPassword`로 비밀번호 변경 |
| DELETE | `/api/worker/me` | JSON 본문의 `currentPassword` 확인 후 논리 탈퇴 |
| GET | `/api/admin/events` | `processed`, `workerId`, `eventType`, `page`, `size`로 조회 |
| GET | `/api/admin/events/{eventId}` | 이벤트 상세 |
| PATCH | `/api/admin/events/{eventId}/process` | JSON 본문 `adminMemo`(1~500자)와 처리 시각·관리자 저장; 이미 처리된 이벤트는 409 |
| POST | `/api/admin/workers` | `workerName`, `sex`, `age`로 생성; 임시 비밀번호 1회 반환 |
| GET | `/api/admin/workers` | 작업자 목록, `page`, `size` 지원 |
| GET | `/api/admin/workers/{workerId}` | 작업자 상세 |
| POST | `/api/admin/workers/{workerId}/reset-password` | 임시 비밀번호 재발급 및 토큰 폐기 |
| GET | `/health` | DB 연결 상태 확인 |

관리자 폼은 `GET /admin/login`, `POST /admin/login`, `POST /admin/logout`을 사용하고 로그인 필드는 `adminId`, `password`입니다. 두 목록 API의 `page` 기본값은 0, `size` 기본값은 20이며 최대 100입니다. 이벤트는 발생 시간과 ID의 내림차순으로 정렬됩니다.

이벤트 등록 예시:

```json
{"time":"2026-11-01T09:00:00Z","eventType":"FALL_DETECTED","alertSender":"ACCELEROMETER"}
```

이벤트 유형은 `FALL_DETECTED`, `DANGER_ZONE_APPROACH`, `ABNORMAL_VITAL`입니다. 성별은 `M`, `F`, `U`, 나이는 0~150입니다. API 시각은 ISO 8601, 관리자 화면 시각은 Asia/Seoul 기준입니다. 처리 메모는 공백 제거 후 저장하며 1~500자여야 합니다. 한 번 처리한 이벤트의 처리자·시각·메모는 유지되고, 재처리 요청은 409 `EVENT_ALREADY_PROCESSED`를 반환합니다. 요청이 실패하면 `{"code":"INVALID_REQUEST","message":"요청 내용을 확인하세요."}` 형식으로 응답합니다. 성공은 200·201·204, 입력 오류는 400, 인증 실패는 401, 권한·CSRF 실패는 403, 없는 항목은 404, 상태 충돌은 409, SQLite 잠금 대기 초과는 503, 내부 오류는 500입니다.

## 테스트

```powershell
.\mvnw.cmd clean verify
```

테스트는 임시 디렉터리에 독립된 SQLite 파일과 무작위 테스트 계정을 만들며 개발 DB를 사용하지 않습니다. 다른 플랫폼에서는 `./mvnw clean verify`를 실행하세요.

## Docker

`.env.example`을 `.env`로 복사하여 최초 관리자 정보를 설정하고 `docker compose up --build -d`를 실행합니다. 기존 관리자 계정이 있으면 재시작에 초기 자격증명이 필요하지 않습니다. `/data`는 `safety-data` 볼륨에 저장됩니다. 운영에서는 HTTPS를 종료하는 신뢰할 수 있는 리버스 프록시 뒤에서 실행하고 HTTPS 요청을 서버로 전달하도록 구성하세요. `prod` 프로필은 세션 쿠키에 Secure와 HttpOnly를 설정합니다.

SQLite WAL은 단일 호스트의 로컬 파일시스템에서 사용하고 애플리케이션 인스턴스는 하나만 실행하세요. SQLite는 한 번에 한 writer만 지원합니다. DB 및 WAL 파일을 Git에 저장하지 마세요. 백업은 실행 중 DB 파일 하나만 복사하지 말고 SQLite의 온라인 백업 기능을 사용합니다. 예를 들어 SQLite CLI가 설치된 호스트에서 `sqlite3 <DB경로> ".backup '<백업경로>'"`를 실행한 뒤, 별도의 DB 파일로 복원하여 조회를 확인하세요. 서버 시작 시 기존 `safety_events` 테이블에 `admin_memo` 열이 없으면 추가합니다. 다른 스키마 변경은 백업 후 버전별 SQL로 수행하며 `schema.sql`의 `IF NOT EXISTS`는 기존 스키마를 변경하지 않습니다.

## 탈퇴와 보존

탈퇴 시 계정을 비활성화하고 이름을 `탈퇴한 작업자`로 변경하며 성별·나이 및 기존 비밀번호를 제거하고 모든 토큰을 폐기합니다. 계정·프로필 ID와 안전 이벤트는 참조 무결성과 이력 보존을 위해 유지됩니다. 이는 물리 삭제나 완전한 익명화가 아닙니다. 이벤트의 보존 기간과 최종 파기 정책은 운영 전에 정해야 합니다.
