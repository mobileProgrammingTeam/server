# IntelliJ와 Docker의 PostgreSQL 공유

IntelliJ와 Docker 앱은 동일한 PostgreSQL 서버에 접속합니다. 프로젝트 루트의 `.env`에 `SPRING_DATASOURCE_URL`과 PostgreSQL 접속 정보가 설정되어 있어야 합니다. IntelliJ 프로필은 이 설정을 읽고, Compose 앱은 `database:5432` 주소로 같은 PostgreSQL 컨테이너를 사용합니다.

## 실행

PostgreSQL만 시작하거나 준비 상태를 확인합니다.

```powershell
docker compose up -d database
docker compose ps
```

IntelliJ에서 Spring Boot 실행 구성을 시작하면 `http://localhost:8080`에서 접근할 수 있습니다. Docker 앱은 다음 명령으로 시작하며 `http://localhost:8081`에서 접근할 수 있습니다.

```powershell
docker compose up --build -d
```

두 앱은 동일한 PostgreSQL 데이터를 읽고 씁니다. 데이터는 `postgres-data` Docker 볼륨에 유지됩니다. `docker compose down -v` 명령은 이 데이터 볼륨도 삭제합니다.