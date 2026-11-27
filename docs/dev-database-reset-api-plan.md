# 개발용 DB 초기화 API 구현 계획서

작성일: 2026-11-13 (Asia/Seoul)

이 문서는 구현 계획이다. 신규 API, 초기화 SQL 실행, 실제 `.env` 변경은 이번 작업에 포함하지 않는다. 기존 로그인 보호 구현의 검증 결과는 [검증 보고서](login-audit-verification.md)에 별도로 기록한다.

## 1. 요청과 적용 가정

사용자가 요청한 기능은 개발 중 수동으로 호출하는 DB 데이터 초기화 API다. 요청마다 `.env`의 `seceret_key`를 읽고, 전달받은 값이 일치할 때만 초기화한다. 키를 해싱하지 않는다. Kotlin·Thymeleaf·관리자 JavaScript에 버튼이나 호출 코드를 추가하지 않고, 개발 종료 시 API와 설정을 제거한다.

`seceret_key`는 요청에 적힌 철자를 그대로 사용한다. `secret_key`, `SECRET_KEY` 등 다른 이름으로 자동 대체하지 않는다.

**확정된 초기화 범위는 모든 데이터 삭제 후 최초 관리자 계정 한 개를 재생성하는 것이다.** 기존 작업자·관리자 계정과 업무 데이터, 토큰, 로그인 제한 기록은 모두 제거한다. 테이블·인덱스·외래 키는 유지한다. 재생성할 관리자 ID와 비밀번호는 현재 애플리케이션 설정의 `ADMIN_LOGIN_ID`, `ADMIN_INITIAL_PASSWORD`를 사용하며, 설정이 없거나 비밀번호 정책을 통과하지 못하면 DB를 변경하기 전에 거부한다. 기본값 `0000`으로 대체하지 않는다.

## 2. 현재 코드에서 고려할 사항

| 항목 | 확인된 구조 | 계획에 반영할 내용 |
| --- | --- | --- |
| 데이터 | `accounts`, `worker_profiles`, `safety_events`, `mobile_tokens`, `login_ip_attempts`, `login_ip_blocks` | 삭제 대상을 고정된 목록으로 지정한다. |
| 외래 키 | 프로필·토큰·이벤트가 계정을 참조하고 이벤트의 `processed_by`는 관리자를 참조한다. | 전체 데이터 삭제와 관리자 재생성을 하나의 트랜잭션으로 묶는다. |
| 관리자 초기 생성 | `AdminBootstrap`이 시작 시 `SafetyService.bootstrap`을 실행한다. | 초기화 API에서도 같은 관리자 설정·비밀번호 검증·인코딩 규칙을 재사용한다. |
| 환경 파일 | `local` 프로필이 시작 시 `.env`를 UTF-8 Java Properties로 가져온다. | 요청 시 파일 재조회에는 별도 읽기 코드가 필요하다. |
| Docker | Compose가 일부 환경변수만 전달하며 `.env` 파일을 앱에 마운트하지 않는다. | 개발 전용 Compose 덮어쓰기 파일에서 읽기 전용 마운트를 추가한다. |
| 공유 DB | Docker 앱과 IntelliJ 앱이 동일 PostgreSQL을 사용한다. | 한쪽 API 호출로 양쪽 데이터가 함께 초기화된다. JVM 내부 잠금에 의존하지 않는다. |
| 보안 | 작업자 Bearer 체인과 관리자 세션·CSRF 체인이 분리되어 있다. | 이 경로에만 별도 무상태 체인을 적용한다. |
| 로그 | 요청 로그는 전역 필터, 서비스 AOP는 `SafetyService`만 대상으로 한다. | 신규 초기화 서비스는 별도로 로그 대상에 연결해야 한다. |

근거 파일: [스키마](../src/main/resources/schema-postgresql.sql), [보안 설정](../src/main/java/org/example/workersafety_server/config/SecurityConfig.java), [환경 설정](../src/main/resources/application-local.properties), [Compose](../compose.yaml), [DB 공유 안내](shared-database.md).

## 3. API 계약

| 항목 | 제안 |
| --- | --- |
| 메서드·경로 | `POST /api/dev/database/reset` |
| 승인 값 | `X-Dev-Reset-Key` 헤더의 문자열 |
| 서버 기준 값 | 해당 요청에서 읽은 `.env`의 정확한 `seceret_key` 값 |
| 요청 본문 | 없음 |
| 성공 | DB 커밋 후 `204 No Content` |
| 키 누락·공백·불일치·중복 헤더 | `401` JSON, `DEV_RESET_AUTH_FAILED` |
| 파일 읽기 불가·키 미설정·빈 설정 | `503` JSON, `DEV_RESET_UNAVAILABLE`; DB 변경 없음 |
| 다른 초기화가 실행 중 | `409` JSON, `DEV_RESET_IN_PROGRESS` |
| DB 잠금 시간 초과·경합 | `503` JSON, `DATABASE_BUSY`; 전체 롤백 |
| 예상하지 못한 DB 오류 | `500` JSON, `INTERNAL_ERROR`; 전체 롤백 |
| GET 등 다른 메서드 | 활성화 상태에서 `405`, `Allow: POST`; DB 변경 없음 |
| 캐시 | 성공·실패 모두 `Cache-Control: no-store` |

키는 URL이나 쿼리 파라미터로 받지 않는다. 관리자 세션이나 작업자 토큰이 있어도 키가 없으면 승인하지 않는다. 실패 응답에는 기대 키, 입력 키, 파일 내용, SQL 원문을 담지 않는다.

터미널 전용 사용은 화면 연결 없이 수동 HTTP 요청으로 호출하는 방식으로 정의한다. 서버는 HTTP 요청의 발신 프로그램이 터미널인지 보증할 수 없으므로, 승인 근거는 개발 환경 활성화 조건과 키 일치 여부다. User-Agent를 인증 기준으로 삼지 않는다.

## 4. 개발 환경 활성화와 키 읽기

1. 컨트롤러·키 검사·초기화 서비스·전용 보안 설정을 별도 개발 기능 파일로 묶는다.
2. `local` 프로필이 활성화되고 `prod`가 비활성화된 경우에만 등록한다. 예: `@Profile("local & !prod")`.
3. `DEV_DB_RESET_ENABLED=true`를 명시한 경우에만 등록한다. 기본값은 `false`다.
4. 비활성화·운영 환경에서는 전용 라우트를 등록하지 않는다. 현 보안 체인의 기본 거부 정책에 따라 익명 요청은 401, 인증된 관리자 요청은 403으로 차단되며 DB는 변경되지 않아야 한다.
5. 활성화 조건을 만족하면 정확한 경로에만 우선순위가 높은 보안 체인을 둔다. 세션 생성·폼 로그인·Bearer 인증·요청 캐시를 사용하지 않는다.
6. 이 체인 안에서만 CSRF를 비활성화하고 POST 요청에 헤더 키 검사를 적용한다. 기존 관리자·작업자 체인의 설정은 유지한다. 필터를 서블릿 컨테이너에 중복 등록하지 않는다.

현재 Spring Security는 POST 등 상태를 바꾸는 요청을 기본적으로 CSRF 검사한다. 별도 경로에서 쿠키 대신 명시적인 헤더 키만 승인 수단으로 사용하도록 범위를 한정한다. [Spring Security CSRF 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

계획상 추가 설정은 다음과 같다. 실제 키는 `.env.example`에 채우지 않는다.

```properties
DEV_DB_RESET_ENABLED=false
DEV_RESET_ENV_FILE=./.env
seceret_key=
```

- 기능 플래그와 파일 경로는 시작 시 로딩한다. 키 자체는 매 POST 요청에서 파일을 연 뒤 읽는다.
- `Files.newBufferedReader(path, UTF_8)`와 `Properties.load(Reader)`로 현재 프로젝트의 파일 형식에 맞춘다. `seceret_key` 속성을 정확히 조회한다.
- 파일 경로는 서버 설정으로만 받는다. 요청자가 파일 경로를 선택할 수 없게 한다.
- `@Value("${seceret_key}")`의 시작 시 스냅샷이나 OS 환경변수의 동명 값으로 대체하지 않는다. 파일이 없거나 값이 비어 있으면 승인하지 않는다.
- 입력과 설정 값에 해시·BCrypt를 적용하지 않고 문자열을 직접 비교한다. 임의의 trim이나 대소문자 변환을 하지 않는다.
- 요청 중 한 번 읽은 키로 해당 요청을 판정한다. 읽기 실패 시 이전 값을 캐시해 계속 허용하지 않는다.

Docker 지원은 `compose.dev-reset.yaml` 같은 개발 전용 덮어쓰기 파일에서만 추가한다. `.env`를 `/run/dev-reset.env` 등에 읽기 전용으로 마운트하고 `DEV_RESET_ENV_FILE`을 해당 경로로 지정한다. 기본 Compose의 운영 실행 경로는 활성화하지 않는다. 읽기 전용 마운트는 컨테이너가 원본 파일을 수정하지 못하게 한다. [Docker bind mount 문서](https://docs.docker.com/engine/storage/bind-mounts/).

파일 마운트는 편집기의 원자적 파일 교체 방식에 따라 이전 파일을 계속 가리킬 수 있다. Docker에서 키 파일을 교체한 경우 컨테이너 재생성으로 마운트를 갱신하고 새 키의 적용을 확인한다. 로컬 파일 재조회와 Docker 환경변수 갱신을 같은 동작으로 간주하지 않는다.

## 5. 초기화 트랜잭션

확정된 데이터 정책은 다음과 같다.

| 데이터 | 처리 |
| --- | --- |
| `safety_events` | 처리 여부와 관계없이 전부 삭제 |
| `mobile_tokens` | 전부 삭제해 기존 작업자 토큰 무효화 |
| `worker_profiles` | 전부 삭제 |
| `accounts`의 `WORKER` | 활성·잠금·탈퇴 여부와 관계없이 전부 삭제 |
| `accounts`의 `ADMIN` | 기존 계정 모두 삭제 후 설정된 최초 관리자 한 명을 새 ID와 새 비밀번호 해시로 생성 |
| `login_ip_attempts`, `login_ip_blocks` | 전부 삭제 |
| 테이블·인덱스·외래 키·스키마 | 보존 |
| identity 시퀀스 | `RESTART IDENTITY`; 초기화 후 ID를 초기 상태로 돌림 |
| 관리자 세션 | 이전 관리자 계정의 세션을 모두 만료시키고 재로그인 요구 |

처리 순서:

1. 개발 환경과 헤더 키 승인을 완료한다. 관리자 재생성에 필요한 설정도 검증하고 비밀번호 인코딩을 준비한다. 이 단계가 실패하면 초기화 SQL을 실행하지 않는다.
2. 별도 `DevDatabaseResetService`의 단일 `@Transactional` 메서드로 진입한다. 기존 `SafetyService.bootstrap`의 `TransactionTemplate`이 같은 트랜잭션 매니저에 참여하는지 확인하거나, 공통 관리자 생성 로직을 현재 트랜잭션에서 호출할 수 있게 정리한다. 중첩 호출이 별도 커밋을 만들면 안 된다.
3. PostgreSQL 트랜잭션 advisory lock을 고정된 하나의 키로 시도한다. 다른 인스턴스에서 초기화 중이면 변경 없이 409를 반환한다. 시작 시 관리자 부트스트랩도 같은 잠금을 사용해 초기화와 겹치지 않게 한다.
4. 짧은 `lock_timeout`을 설정하고 관련 테이블을 고정 순서로 잠근다. `login_ip_blocks`를 `login_ip_attempts`보다 먼저 잠가 로그인 제한 트랜잭션의 접근 순서와 맞춘다. 잠금 실패·교착 상태는 전체 롤백하고 503으로 반환한다.
5. PostgreSQL의 트랜잭션 안에서 고정된 전체 테이블 집합을 `TRUNCATE ... RESTART IDENTITY RESTRICT`로 비운 뒤, 같은 트랜잭션에 설정된 최초 관리자 한 명을 생성한다. 알 수 없는 테이블까지 연쇄 삭제하는 `CASCADE`는 쓰지 않는다.
6. 커밋 후에만 204를 반환한다. 초기화 또는 관리자 생성 중 오류가 나면 테이블 데이터·시퀀스 변경을 포함해 모두 롤백한다.

전체 초기화에는 스키마에 있는 테이블을 빠짐없이 명시한 `TRUNCATE ... RESTART IDENTITY RESTRICT`를 사용한다. 참조되는 테이블을 한 문장에 모두 포함하고 `CASCADE`로 범위를 넓히지 않는다. PostgreSQL에서 TRUNCATE와 시퀀스 재시작은 트랜잭션으로 롤백 가능하며, 잠금 경합과 시퀀스 범위도 검증한다. [PostgreSQL 17 TRUNCATE 문서](https://www.postgresql.org/docs/17/sql-truncate.html).

관리자 웹 세션은 현재 코드에서 DB 밖의 HTTP 세션으로 유지되므로 계정 행 삭제만으로 다른 서버 인스턴스의 세션이 만료되지 않는다. 계획에는 관리자 인증 성공 시 세션에 비밀이 아닌 자격 증명 버전 표식을 저장하고, 관리자 요청마다 현재 계정의 비밀번호 해시와 표식을 비교해 초기화 전 세션을 거부·무효화하는 처리를 포함한다. 새 관리자는 새 BCrypt 해시를 받으므로 같은 로그인 ID와 비밀번호 설정을 재사용해도 이전 세션 표식과 일치하지 않는다. 키 해싱 금지 요구는 초기화 키에만 적용되며 관리자 비밀번호는 기존 BCrypt 정책을 유지한다. 이 임시 세션 무효화 코드는 API 제거 시 함께 제거한다.

초기화 중에는 Docker·IntelliJ 양쪽의 일반 API 호출과 테스트 데이터 생산을 멈춘다. DB 잠금은 트랜잭션 동안의 작업 순서를 보장하지만, 커밋 뒤 대기 요청이 새 데이터를 생성하는 것까지 막는 것은 아니다. 성공의 의미는 초기화 트랜잭션이 커밋되었다는 것이다. 별도 유지보수 화면이나 전체 서비스 유지보수 모드는 추가하지 않는다.

## 6. 로그와 제거 구조

- 기존 요청 로그의 `requestId`, 경로, 상태, 소요 시간을 사용한다.
- 키를 승인한 요청은 `DEV_TOOL` 같은 요청자 구분으로 기록한다. 존재하지 않는 관리자나 작업자 ID를 부여하지 않는다.
- 현재 서비스 AOP의 대상은 `SafetyService`뿐이다. 신규 메서드에 대한 명시적 로그 대상을 추가해 커밋 포함 성공·실패를 기록하고, 제거 시 그 대상도 함께 삭제한다.
- 키 값, 전체 헤더, `.env` 내용, 삭제된 개인정보는 로그에 남기지 않는다. 키 비교 실패 시에도 원문을 출력하지 않는다.

예상 변경 파일:

| 파일 | 역할 |
| --- | --- |
| `controller/api/dev/DevDatabaseResetController.java` | POST 라우트와 204 응답 |
| `service/dev/DevDatabaseResetService.java` | DB 잠금과 단일 초기화 트랜잭션 |
| `security/dev/DevResetKeyFilter.java` 또는 동등한 전용 검사 | 요청 시 파일 읽기와 헤더 비교 |
| `config/DevResetSecurityConfig.java` | 개발 조건과 정확한 경로의 무상태 체인 |
| `application-local.properties`, `.env.example` | 기능 플래그·파일 경로와 빈 키 예시 |
| `compose.dev-reset.yaml` | 개발 실행에만 파일 마운트 |
| 서비스 로그 설정·통합 테스트·README | 결과 연결, 회귀 검사, 수동 호출 안내 |

개발 종료 시 위 기능 파일·체인·로그 대상·설정·Compose 덮어쓰기 파일·호출 예시를 제거하고 `.env`의 키를 폐기한다. 기존 로그인 보호·관리자 API·스키마는 제거하지 않는다. 삭제 후에는 해당 경로로 데이터를 변경할 수 없는지 검증한다.

## 7. 터미널 사용 예시

다음은 구현 후 사용할 PowerShell 예시다. 현재 실행 가능한 신규 API는 아니다. 키를 명령문에 직접 적는 대신 입력받는다.

```powershell
$resetKey = Read-Host 'seceret_key' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('dev-reset', $resetKey)
$headers = @{ 'X-Dev-Reset-Key' = $credential.GetNetworkCredential().Password }
try {
    Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri 'http://127.0.0.1:8080/api/dev/database/reset' `
        -Headers $headers
} finally {
    $headers.Clear()
    $credential = $null
    $resetKey.Dispose()
}
```

Docker 앱을 대상으로 할 때는 실제 개발 노출 포트(기본 8081)를 사용한다. Kotlin·Thymeleaf·관리자 JS의 호출 연결은 만들지 않는다.

## 8. 구현 순서와 완료 조건

1. 확정된 범위(모든 기존 데이터 삭제 후 설정된 최초 관리자 한 명 재생성)를 기준으로 DB 테이블 전체 목록, 관리자 설정 출처, 세션 무효화 방식을 구현 전에 고정한다.
2. 파일 읽기와 키 검사, 개발 활성화 조건을 구현한다. 임시 `.env`만 사용하는 테스트로 누락·공백·불일치·정확한 철자·키 변경 후 재조회·읽기 실패를 검사한다.
3. PostgreSQL 초기화 서비스를 구현한다. 기존 데이터를 채운 전용 Testcontainers DB에서 모든 기존 행 삭제, 정확히 한 명의 새 관리자 생성, 재생성 관리자 로그인, 시퀀스 재시작, 중간 오류 시 데이터·시퀀스 전체 롤백, 같은 요청 반복 실행을 검사한다.
4. 보안 체인과 HTTP 응답을 연결한다. 올바른 키만 승인되고 관리자 세션·작업자 토큰만으로는 승인되지 않는지 검사한다. 비활성·prod·local+prod 조합에서는 데이터가 보존되어야 한다.
5. 동일 DB에 연결한 두 실행 주체의 초기화 경쟁, 관리자 시작 부트스트랩·기존 관리자 세션·일반 쓰기·IP 정리와의 경합, 잠금 시간 초과를 검사한다. 초기화 전 관리자는 재생성 계정과 같은 ID를 쓰더라도 기존 세션으로 접근할 수 없어야 한다. 부분 삭제나 승인 없는 SQL 실행이 없어야 한다.
6. POST 외 메서드 거절, no-store, 키가 응답·로그에 없는지, 기존 관리자 CSRF와 로그인 보호가 유지되는지 검사한다.
7. Docker 개발 파일 마운트와 터미널 호출을 확인한 뒤 `mvn clean verify`를 통과시킨다. 실제 개발 데이터 초기화 호출은 별도의 실행 작업으로 다룬다.

현재 검증에서 발견된 계정 상태 갱신·IP 정리 경쟁과 기존 경쟁 테스트의 기대값 문제는 신규 API와 별도로 먼저 보완하는 것이 좋다. 검증 보고서에 재현 순서와 수정 방향을 기록한다.
