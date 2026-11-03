# Worker Safety Server

작업자 안전 이벤트를 저장·조회·처리하는 서버입니다. Android 앱용 REST API와 관리자 웹을 제공합니다.

- Java 17 · Maven · Spring Boot · Spring Security · Thymeleaf · JPA · PostgreSQL
- 작업자 계정 생성, 로그인, 프로필 관리, 안전 이벤트 등록 및 처리 지원
- 미구현 도메인 API는 고정 응답을 반환하며, 관리자 화면은 실제 데이터 또는 빈 상태를 표시

## 로컬 실행

JDK 17을 설치하고 프로젝트 루트에서 실행합니다.

1. `.env.example`을 `.env`로 복사합니다.
2. `ADMIN_LOGIN_ID`, `ADMIN_INITIAL_PASSWORD`에 최초 관리자 정보를 입력합니다.
3. 서버를 실행합니다.

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux에서는 `./mvnw spring-boot:run`을 사용합니다. IntelliJ에서는 Working directory를 프로젝트 루트로 설정합니다.

- 관리자 로그인: http://localhost:8080/admin/login
- 상태 확인: http://localhost:8080/health

## 설정

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| `ADMIN_LOGIN_ID` | 최초 관리자 ID | 관리자 계정이 없을 때 필수 |
| `ADMIN_INITIAL_PASSWORD` | 최초 관리자 비밀번호 | 관리자 계정이 없을 때 필수 |
| `SPRING_DATASOURCE_URL` | Shared PostgreSQL connection | IntelliJ `localhost:5432`, Docker `database:5432` |
| `SERVER_PORT` | IntelliJ application port | `8080` |
| `DOCKER_SERVER_PORT` | Docker application port on the host | `8081` |
| `MOBILE_TOKEN_TTL` | 작업자 토큰 유효 기간 | `PT12H` |
| `SPRING_PROFILES_ACTIVE` | 실행 프로필 | 로컬 `local`, Compose 미지정 시 `prod` |

최초 관리자 비밀번호는 12~64자, UTF-8 72바이트 이하여야 합니다. 기존 관리자 계정은 초기 설정으로 덮어쓰지 않습니다.

`local` 프로필은 `.env`를 UTF-8 Java Properties로 읽습니다. `KEY=value` 형식으로 작성하고 따옴표나 `export`를 붙이지 마세요. Windows 경로에는 `/`를 사용하세요. OS 환경변수는 파일보다 우선하며, 설정 변경 후에는 서버를 재시작합니다. `.env`와 비밀값은 Git에 저장하지 마세요.

## API

작업자는 `/api/worker/auth/login`으로 로그인한 뒤 `Authorization: Bearer <accessToken>`을 사용합니다. 관리자 API는 웹 로그인 세션과 쓰기 요청의 CSRF 토큰이 필요합니다.

- [작업자 API](src/main/java/org/example/workersafety_server/controller/api/WorkerController.java): 인증, 이벤트 등록, 프로필·비밀번호 변경, 탈퇴
- [관리자 API](src/main/java/org/example/workersafety_server/controller/api/AdminApiController.java): 작업자 생성·조회·비밀번호 초기화, 이벤트 조회·처리
- [DB schema](src/main/resources/schema-postgresql.sql)

## 테스트

```powershell
.\mvnw.cmd clean verify
```

테스트는 임시 PostgreSQL database를 사용합니다. 실행 중인 개발·테스트 서버의 API는 다음 도구로 확인할 수 있습니다.

```powershell
python -m pip install httpx
python tools/api_tester.py
```

API 테스터는 서버 주소와 관리자 정보를 입력받습니다. 테스트 작업자를 생성한 뒤 탈퇴시키며, 계정과 이벤트 이력은 DB에 남습니다.

## Docker

Docker and the IntelliJ local profile both connect to the same PostgreSQL database. Start the database before running the application from IntelliJ:

```powershell
docker compose up -d database
```

Run the Docker application at `http://localhost:8081` with `docker compose up --build -d`. The IntelliJ application uses `http://localhost:8080`. Both show the same data.

PostgreSQL data is stored in the `postgres-data` Docker volume. `docker compose down -v` deletes it.

See [shared database setup](docs/shared-database.md) for run instructions.
