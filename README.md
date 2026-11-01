# server
"모바일 앱 -> 서버 -> 관리자 화면" 흐름에서 사용될 서버입니다.

## 현재 프로젝트

`workersafety_server`는 Spring Boot 기반 서버 프로젝트입니다. 현재 애플리케이션 진입점과 기본 컨텍스트 테스트가 있으며, API와 관리자 화면은 아직 구현되어 있지 않습니다.

### 기술 구성

- Java 17, Spring Boot 4.1.2, Maven Wrapper
- Spring Web MVC, Spring Data JPA, Spring Security
- Thymeleaf, Validation, Lombok

### 실행 및 테스트

아래 명령은 `pom.xml`과 Maven Wrapper가 있는 현재 프로젝트 디렉터리에서 실행합니다. 이 저장소에는 현재 README만 포함되어 있습니다.

```bash
# Windows
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test

# macOS / Linux
./mvnw spring-boot:run
./mvnw test
```

JPA를 사용하므로 실행과 컨텍스트 테스트 전에 사용할 데이터베이스의 JDBC 드라이버 의존성과 연결 설정을 추가해야 합니다. 현재 프로젝트의 `application.properties`에는 애플리케이션 이름만 설정되어 있습니다.
