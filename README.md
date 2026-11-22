# 근로자 안전 관리 서버

근로자 안전 이벤트, 근로자 계정, 관리자 기능을 제공하는 REST API 서버입니다.

- Java 17, Maven, Spring Boot, Spring Security, Thymeleaf, Spring Data JPA, PostgreSQL
- 근로자 계정 생성, 로그인, 프로필 관리, 안전 이벤트 보고 및 관리자 콘솔 제공

## Docker로 실행

Docker와 Docker Compose를 설치하고 Docker를 실행합니다. 프로젝트 루트에 `.env` 파일이 없다면 예제 파일을 복사합니다.

```powershell
Copy-Item .env.example .env
```

`.env`에서 다음 값을 설정합니다.

- `POSTGRES_PASSWORD`: 사용할 데이터베이스 비밀번호로 변경합니다.
- `ADMIN_LOGIN_ID`, `ADMIN_INITIAL_PASSWORD`: 초기 관리자 계정을 설정합니다.
- `SPRING_PROFILES_ACTIVE=local`: 로컬 HTTP 접속에 사용합니다. `.env.example`의 기본값입니다.
- `DOCKER_SERVER_PORT=8081`: 서버에 접속할 호스트 포트입니다.

다음 명령어로 서버 이미지를 빌드하고 PostgreSQL과 서버를 백그라운드에서 실행합니다. JDK와 Maven은 Docker 이미지 내부에서 사용하므로 별도로 설치할 필요가 없습니다.

```powershell
docker compose up -d --build
```

기본 설정에서 관리자 콘솔은 `http://localhost:8081/admin/login`, 서버 상태 확인 주소는 `http://localhost:8081/health`입니다. `DOCKER_SERVER_PORT`를 변경했다면 해당 포트로 접속합니다.

서버 로그를 확인하려면 다음 명령어를 실행합니다.

```powershell
docker compose logs -f server
```

종료할 때는 다음 명령어를 실행합니다. 데이터베이스 데이터는 `postgres-data` 볼륨에 유지됩니다.

```powershell
docker compose down
```

## API

근로자는 `POST /api/worker/auth/login`으로 로그인합니다. 근로자 API를 호출할 때는 반환된 토큰을 `Authorization: Bearer <accessToken>` 헤더에 담아 전송합니다. 관리자 API는 관리자 로그인 세션과 CSRF 토큰을 사용합니다.

### 로그인한 근로자 프로필 조회

`GET /api/worker/me`는 Bearer 토큰으로 식별된 근로자의 프로필을 반환합니다. 근로자 ID를 매개변수로 받지 않습니다. 인증되지 않았거나 토큰이 만료, 폐기 또는 그 밖의 사유로 유효하지 않은 경우 HTTP 401을 반환합니다. 응답에는 `Cache-Control: no-store` 헤더가 적용됩니다.

요청 예시:

```http
GET /api/worker/me
Authorization: Bearer <accessToken>
```

응답 예시 (`200 OK`):

```json
{
  "workerId": 1024,
  "workerName": "홍길동",
  "sex": "M",
  "age": 25,
  "enabled": true,
  "loginLocked": false,
  "deleted": false
}
```

`workerId`는 숫자, `workerName`은 문자열, `sex`는 문자열(`M`, `F`, `U`) 또는 `null`, `age`는 숫자 또는 `null`입니다. `enabled`, `loginLocked`, `deleted`는 불리언 값입니다. 응답은 기존 `WorkerInfo` DTO를 사용하며, 프로필 필드와 함께 계정 상태 필드를 포함합니다.

그 밖의 API 오류는 기존 `{ "code": "...", "message": "..." }` 응답 형식을 사용합니다. `PATCH /api/worker/me`는 로그인한 근로자의 프로필을 수정하며, GET 엔드포인트는 저장된 값을 반환합니다.

- [근로자 API](src/main/java/org/example/workersafety_server/controller/api/WorkerController.java)
- [관리자 API](src/main/java/org/example/workersafety_server/controller/api/AdminApiController.java)
- [데이터베이스 스키마](src/main/resources/schema-postgresql.sql)

## 테스트

테스트는 Testcontainers를 통해 임시 PostgreSQL 데이터베이스를 사용합니다. 다음 명령어로 실행합니다.

```powershell
.\mvnw.cmd clean verify
```
