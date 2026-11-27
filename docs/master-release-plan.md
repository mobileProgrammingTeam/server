# master 서버 릴리스 및 GCP 실행 계획서

작성일 2026-11-23 (KST)

목표는 서버 완성 후 `master`의 승인된 릴리스 태그를 GCP에서 클론해 실행하는 것이다. 이 계획은 **Compute Engine Linux VM 1대에서 Docker Compose로 서버와 PostgreSQL을 실행**한다고 가정한다. 서버 기본 브랜치와 별개로 릴리스 브랜치는 `master`를 사용한다. 모바일 앱 버전과 앱 인수 검증은 모바일 담당자가 결정한다. [Compute Engine 컨테이너 실행 문서](https://docs.cloud.google.com/compute/docs/containers)

## 승인 요청안

**서버 버전은 `1.0.0`, Git 태그는 `v1.0.0`을 권장한다.** 계정 관리와 안전 이벤트 처리에 필요한 서버 API를 검증하고 GCP에서 재현할 수 있는 첫 정식 기준점으로 삼는다. 태그는 최종 검증을 마친 `master` 커밋에 붙인다. [Semantic Versioning](https://semver.org/)

| 범위 | 권장안 |
| --- | --- |
| 작업자 API | 로그인·로그아웃, 내 프로필 조회·수정, 비밀번호 변경·탈퇴, 안전 이벤트 보고 |
| 관리자 | 로그인, 작업자 생성·조회·삭제·비밀번호 초기화, 안전 이벤트 조회·처리, 단일 인스턴스에서 관리자 화면 SSE 자동 갱신 |
| 실행 | PostgreSQL 17과 `/health`, GCP VM용 클론·빌드·실행 절차. 현재 날씨 조회는 현장 좌표가 설정되고 VM에서 실제 조회가 확인된 경우 사용한다. |
| 후속 범위 | 샘플 기반 현장 구역·기기 상태, 관리자 계정 추가·권한 변경, 날씨 공지 발송, 모의 SOS 발송·안전 확인 요청. 실제 기능으로 오인되지 않도록 릴리스 화면과 API에서 사용 가능 범위를 정리한다. |

지원 API의 경로·인증 방식·요청 및 응답을 서버 기준으로 기록해 모바일 담당자에게 전달한다. [작업자 API](../src/main/java/org/example/workersafety_server/controller/api/WorkerController.java) · [관리자 API](../src/main/java/org/example/workersafety_server/controller/api/AdminApiController.java) · [관리자 예시 API](../src/main/java/org/example/workersafety_server/controller/api/AdminDomainApiController.java)

## Codex Agent가 수행할 작업

| 순서 | 작업 | 완료 기준 |
| --- | --- | --- |
| 1 | 원격 `master`와 `integration/dev-merge`를 다시 대조하고 승인된 범위의 변경을 PR로 준비한다. | 포함할 변경과 대상 브랜치 `master`가 PR에 명시된다. |
| 2 | 작업자 생성·초기화는 요청된 공통 초기 비밀번호 `0000`을 사용한다. 초기화 결과가 프로필 수정으로 덮이는 문제와 로그인 요청이 만료 기록 정리와 겹칠 때 실패하는 문제를 해결한다. | 생성·초기화 응답과 로그인에서 `0000`이 동작하고, 기존 토큰 폐기 및 두 동시성 문제에 대한 회귀 검사가 통과한다. [기존 검증 보고서](login-audit-verification.md) |
| 3 | 승인된 기능과 관리자 화면·API를 일치시킨다. 후속 범위의 샘플 데이터와 모의 성공 응답은 실제 운영 경로에서 사용하지 않도록 처리한다. 서버 API 예제 요청으로 작업자 로그인, 프로필 조회, 이벤트 보고를 검증한다. | 관리자에게 표시되는 기능과 공개한 서버 API 목록이 검증 결과와 일치한다. |
| 4 | 승인된 버전으로 POM과 Docker 빌드의 JAR 경로를 맞춘다. README에 태그 클론, `.env` 준비, `prod` 실행, `/health`, 재기동과 DB 볼륨 보존 절차를 추가한다. 외부 접속에는 HTTPS를 적용하고, 관리자 자동 갱신을 위해 프록시를 경유하는 SSE도 확인하도록 문서화한다. | 새 체크아웃에서 문서의 명령으로 동일한 버전의 서버를 기동한다. [pom.xml](../pom.xml) · [Dockerfile](../Dockerfile) · [SSE 운영 조건](admin-sse-refresh-plan.md#6-장애-복구와-운영-조건) |
| 5 | PR과 병합된 `master` 커밋에서 `clean verify`, 캐시 없는 Docker 빌드, 새 PostgreSQL에서 `prod` 기동을 검증한다. 기존 DB를 옮길 계획이라면 이전 스키마의 테스트 복사본에서도 업그레이드를 검증한다. 검증을 마친 `master` 커밋에 승인된 태그를 만들고, 저장소에 쓰기 권한이 있으면 원격에 게시한다. | 테스트 결과, `/health`, 관리자 로그인, 테스트 이벤트 저장·조회 결과를 기록하고 원격 태그가 검증한 `master` 커밋 SHA를 가리키는지 확인한다. [DB 스키마](../src/main/resources/schema-postgresql.sql) |

`integration/dev-merge`의 `9ce24a6`에서는 Maven 테스트 24개와 캐시 없는 Docker 빌드가 통과했다. 이 결과는 변경 후 `master`와 GCP에서 다시 확인할 항목을 정하는 기준이다.

## 사용자가 수행할 작업

1. **서버 `1.0.0`과 권장 범위를 승인한다.** 작업자 생성과 비밀번호 초기화에 공통 비밀번호 `0000`을 사용한다는 운영 조건을 확인한다.
2. **GCP VM과 접속 환경을 준비한다.** VM에 Git·Docker·Compose를 준비하고 저장소 접근 권한과 `.env`의 실제 비밀값을 설정한다. 외부에서 접속할 경우 HTTPS와 방화벽을 설정한다. 기존 DB를 옮길 경우에만 백업과 복원 가능 여부를 먼저 확인한다.
3. **`master` 병합을 승인하고 GCP에서 실행한다.** 승인된 `v1.0.0` 태그를 클론해 기록된 커밋 SHA와 대조한다. `.env` 설정 후 `docker compose up -d --build`로 서버와 DB를 실행한다.
4. **서버 인수를 확인한다.** GCP에서 `/health`, 관리자 로그인, 테스트 이벤트 저장·처리와 화면 자동 갱신을 확인한다. 모바일 담당자에게 서버 주소, 태그와 API 검증 결과를 전달한다.

## 실행 완료 기준

서버 태그와 커밋 SHA가 일치하고, GCP VM에서 서버 상태가 `UP`이며 관리자 로그인, 테스트 이벤트 저장·처리, SSE 자동 갱신이 동작하면 서버 인수를 완료한다. DB 초기화, 로그인 또는 이벤트 저장에 실패하면 원인을 확인한 뒤 재시도한다. 기존 서비스 교체 중 문제가 생기면 스키마 호환성을 확인하고 이전 태그로 돌아간다. DB 복원이 필요한 경우 쓰기를 중지한 뒤 복원 범위를 확정한다.
