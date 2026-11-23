# 근로자 안전 관리 서버

근로자 안전 이벤트, 근로자 계정, 관리자 기능을 제공하는 REST API 서버입니다.

- Java 17, Maven, Spring Boot, Spring Security, Thymeleaf, Spring Data JPA, PostgreSQL
- 근로자 계정 생성, 로그인, 프로필 관리, 안전 이벤트 보고 및 관리자 콘솔 제공

## 로컬 Docker 실행

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

## GCP Compute Engine에서 실행

Linux VM 1대에 Git, Docker Engine, Docker Compose 플러그인을 준비하고 저장소를 클론합니다. 승인된 릴리스 태그로 체크아웃한 뒤 `.env`를 만들고 운영 비밀값을 설정합니다.

```bash
git clone https://github.com/mobileProgrammingTeam/server.git
cd server
git checkout v1.0.0
cp .env.example .env
chmod 600 .env
```

`.env`에서 `POSTGRES_PASSWORD`, `ADMIN_LOGIN_ID`, `ADMIN_INITIAL_PASSWORD`를 안전한 값으로 설정하고 `SPRING_PROFILES_ACTIVE=prod`로 지정합니다. 날씨 조회를 쓸 때는 현장의 위도와 경도를 설정합니다. `.env`를 저장소에 커밋하거나 로그에 출력하지 않습니다.

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 server
curl --fail http://127.0.0.1:8081/health
```

Compose는 DB 포트만 VM의 loopback에 공개하고 서버는 VM 인터페이스의 8081 포트에 공개합니다. VM 방화벽과 GCP 방화벽은 필요한 외부 HTTPS 프록시만 허용하도록 구성합니다. 직접 인터넷에 공개하는 경우 8081 포트는 외부에 열지 말고 TLS 프록시 뒤에 둡니다. 관리자 로그인과 세션을 사용하는 관리 화면은 HTTPS 접속이 필요합니다. SSE 프록시는 응답 버퍼링을 끄고 연결 유지 시간을 25초 heartbeat보다 길게 설정합니다. VM 한 대에서 서버 하나만 실행합니다.

서버를 갱신할 때 새 태그를 체크아웃하고 같은 Compose 명령으로 다시 빌드합니다. `docker compose down`은 DB 볼륨을 유지합니다. `docker compose down -v`는 운영 데이터를 삭제하므로 사용하지 않습니다. 이전 이미지로 롤백하기 전에 새 스키마와 호환되는지 확인하고, DB 복원은 백업과 복원 범위를 확인한 뒤 수행합니다.

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
