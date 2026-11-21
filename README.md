# Worker Safety Server

REST API for worker safety events, worker accounts, and administration.

- Java 17, Maven, Spring Boot, Spring Security, Thymeleaf, Spring Data JPA, PostgreSQL
- Worker account creation, login, profile management, safety event reporting, and an admin console

## Run locally

Install JDK 17 and run from the project root:

```powershell
.\mvnw.cmd spring-boot:run
```

Configure the initial administrator with `ADMIN_LOGIN_ID` and `ADMIN_INITIAL_PASSWORD`. The admin console is at `http://localhost:8080/admin/login`; health is available at `http://localhost:8080/health`.

## API

Workers log in through `POST /api/worker/auth/login`. Send the returned token as `Authorization: Bearer <accessToken>` to worker APIs. Admin APIs use the admin login session and CSRF token.

### Get the signed-in worker profile

`GET /api/worker/me` returns the profile belonging to the worker identified by the bearer token. It does not accept a worker ID parameter. Unauthenticated, expired, revoked, or otherwise invalid tokens receive HTTP 401. The response uses `Cache-Control: no-store`.

Example request:

```http
GET /api/worker/me
Authorization: Bearer <accessToken>
```

Example response (`200 OK`):

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

`workerId` is a number, `workerName` is a string, `sex` is a string or `null` (`M`, `F`, or `U`), `age` is a number or `null`, and `enabled`, `loginLocked`, and `deleted` are booleans. The response uses the existing `WorkerInfo` DTO, which includes account status fields in addition to the profile fields.

Other API errors use the existing `{ "code": "...", "message": "..." }` response format. `PATCH /api/worker/me` continues to update the signed-in worker's profile; the GET endpoint returns the saved values.

- [Worker API](src/main/java/org/example/workersafety_server/controller/api/WorkerController.java)
- [Admin API](src/main/java/org/example/workersafety_server/controller/api/AdminApiController.java)
- [Database schema](src/main/resources/schema-postgresql.sql)

## Tests

The tests use a temporary PostgreSQL database via Testcontainers:

```powershell
.\mvnw.cmd clean verify
```
