# 로그인 보호·API 로그 구현 검증 보고서

검증일: 2026-11-13 (Asia/Seoul)

## 판정

현재 구현은 추가 보완이 필요하다. 기존 전체 테스트 재실행에서 21개 중 20개가 통과했고 1개가 실패했다. 별도 PostgreSQL에서 기존 테스트가 다루지 않는 동시 실행 순서를 검사한 결과, 비밀번호 초기화 결과 덮어쓰기와 IP 상태 행 삭제 경쟁을 재현했다.

앞선 21개 테스트 통과 기록만으로 이 동시성 문제까지 해결되었다고 판단할 수 없다.

## 검증 환경과 변경 범위

- JDK 17, Maven, Docker의 PostgreSQL 17 Testcontainers를 사용했다.
- 원본 프로젝트에서 `mvn -B clean verify`를 실행했다. 컴파일은 통과했으며 테스트 실패로 verify는 실패했다.
- 추가 검사는 `target/implementation-audit`의 임시 POM과 테스트 소스로 실행했다. 현재 `src/main/java`를 그대로 컴파일했고, 독립된 `implementation_audit` DB를 사용했다.
- 이번 검증에서 서버 소스·기존 테스트·실제 `.env`를 수정하지 않았다. 개발 DB·운영 DB 초기화도 실행하지 않았다.
- 저장되는 문서는 이 검증 보고서와 [개발용 DB 초기화 API 계획서](dev-database-reset-api-plan.md)다.

## 결과

| 검사 | 통과 | 실패 | 실행 오류 | 의미 |
| --- | ---: | ---: | ---: | --- |
| 기존 전체 테스트 | 20 | 1 | 0 | 컴파일 성공, 전체 verify 실패 |
| 추가 동시성·정상 순서 검사 | 1 | 2 | 0 | 기존 테스트의 모호성과 실제 경쟁 결함 확인 |

기존 테스트가 확인한 항목에는 로그인 IP 한도와 경계, 12개 동시 요청 중 10개 통과, 관리자·앱 로그인 합산, 관리자 CSRF 실패 제외, 계정 잠금 후 토큰 거절, 관리자 초기화, JSON 로그 연결, 이전 IP 시도 테이블의 반복 마이그레이션이 포함된다. 이들은 이번 실행에서 통과했다.

## 1. [P1] 프로필 수정이 커밋된 비밀번호 초기화를 되돌림

위치: [SafetyService.java](../src/main/java/org/example/workersafety_server/service/SafetyService.java)의 `updateProfile`(128행, 147행), [Account.java](../src/main/java/org/example/workersafety_server/entity/Account.java).

`updateProfile`은 계정을 일반 조회한 뒤 `updatedAt`을 변경한다. 계정에 대한 비관적 잠금이나 버전 검사가 없고, 현재 엔티티는 기본 전체 컬럼 UPDATE를 사용한다. 프로필 수정 트랜잭션이 먼저 읽은 비밀번호 해시를 나중에 계정에 다시 저장할 수 있다.

실제 재현 순서:

1. 작업자를 생성하고 비밀번호를 `0000`과 다른 값으로 변경한다.
2. 별도 DB 연결이 해당 계정 행을 잠근다.
3. `resetPassword`를 실행해 계정 잠금을 기다리게 한다.
4. `updateProfile`을 실행한다. 이전 계정 값을 읽은 뒤 계정 UPDATE에서 대기한다.
5. 잠금을 해제한다. 대기 순서에 따라 초기화가 먼저 커밋되고 프로필 수정이 다음으로 커밋된다.
6. 초기화 이전 비밀번호로 로그인한다.

예상 결과는 일반 401을 발생시키는 `ApiException`이지만 실제로는 로그인이 성공했다. 추가 검사 `profileUpdateMustNotUndoACommittedPasswordReset`가 이 이유로 실패했다. 같은 전체 계정 갱신 방식은 실패 횟수·잠금·활성 상태에도 영향을 줄 수 있다. 이번 검사에서 직접 확인한 값은 비밀번호다.

수정 방향: 프로필 변경도 계정의 동일한 행 잠금 규칙에 포함한다. 이미 읽은 엔티티에 나중에 잠금만 붙이지 말고, 계정 값을 읽기 전에 잠금을 얻도록 순서를 맞춘다. 수정 후 초기화·로그인 잠금·탈퇴와 프로필 수정의 경쟁을 검사한다.

## 2. [P2] IP 정리 작업이 로그인 필터의 상태 행을 먼저 삭제

위치: [LoginAttemptFilter.java](../src/main/java/org/example/workersafety_server/security/LoginAttemptFilter.java)의 42~43행, [LoginAttemptCleanup.java](../src/main/java/org/example/workersafety_server/security/LoginAttemptCleanup.java)의 23행.

로그인 필터는 `INSERT ... ON CONFLICT DO NOTHING` 다음에 별도 `SELECT ... FOR UPDATE`를 실행한다. 기존 행에 충돌해 INSERT를 건너뛰면, SELECT로 잠그기 전까지 정리 작업이 해당 행을 삭제할 수 있다.

실제 재현 순서:

1. 최근 시도 기록이 없고 `blocked_until = 0`인 IP 행을 만든다.
2. 로그인 필터와 같은 트랜잭션에서 INSERT-if-absent를 실행한다. 기존 행이 있으므로 INSERT는 건너뛴다.
3. 다른 스레드·DB 연결에서 실제 `cleanupExpiredAttempts()`를 실행한다.
4. 원래 트랜잭션에서 같은 SELECT FOR UPDATE를 실행한다.

조회 결과는 1행이 아니라 0행이었다. 추가 검사 `cleanupMustNotRemoveIpStateBeforeTheFilterCanLockIt`가 실패했다. 현재 필터의 `queryForObject`는 이 경우 `EmptyResultDataAccessException`을 던지므로 인증 진입이 예외로 끝날 수 있다. 이번 재현은 DB 연산과 실제 정리 메서드의 경쟁을 확인한 것이며, 웹 서버에서의 최종 오류 응답까지 별도로 검사한 것은 아니다.

수정 방향: 기존 행 충돌 시에도 행 잠금과 조회 결과를 한 SQL에서 얻는 upsert-returning 방식 등으로 INSERT와 잠금 사이의 삭제 가능 구간을 없앤다. 실제 정리 작업과 필터를 병행하는 회귀 검사를 추가한다.

## 3. [P2] 기존 로그인/초기화 경쟁 테스트의 기대값이 모호함

위치: [WorkersafetyServerApplicationTests.java](../src/test/java/org/example/workersafety_server/WorkersafetyServerApplicationTests.java)의 `loginRacingPasswordResetCannotLeaveOldTokenValid`(269행).

작업자 생성과 초기화 결과가 모두 `0000`이다. 이 테스트는 생성 당시 비밀번호를 그대로 사용해 로그인과 초기화를 경쟁시킨다. 초기화가 먼저 끝난 다음 로그인하면 새 비밀번호 `0000`으로 정상 토큰이 발급된다. 현재 테스트는 그 토큰도 무효여야 한다고 검사해 실행 순서에 따라 실패한다.

이번 전체 실행에서 해당 테스트가 실패했다. 별도 검사 `resetFollowedByLoginWith0000IsAValidSequence`에서는 초기화 후 `0000`으로 발급한 토큰이 정상 인증되어야 한다는 기대가 통과했다. 따라서 이 기존 테스트 실패만으로 이전 자격증명의 토큰 폐기 구현이 잘못되었다고 단정할 수 없다.

수정 방향: 먼저 작업자 비밀번호를 `0000`과 다른 값으로 변경한 뒤, 그 이전 비밀번호를 사용하는 로그인과 초기화를 경쟁시킨다. 초기화 뒤의 올바른 새 비밀번호 로그인과 구분해야 한다.

## 재현 산출물

- 원본 전체 실행 로그: `target/implementation-verification.log`
- 원본 결과: `target/surefire-reports/TEST-org.example.workersafety_server.WorkersafetyServerApplicationTests.xml`
- 추가 검사 소스: `target/implementation-audit/tests/org/example/workersafety_server/ImplementationAuditTests.java`
- 추가 검사 실행 로그: `target/implementation-audit/audit.log`
- 추가 검사 결과: `target/implementation-audit/target/surefire-reports/TEST-org.example.workersafety_server.ImplementationAuditTests.xml`

`target` 산출물은 다음 clean 작업에서 지워질 수 있다. 재현 절차와 최종 판정은 이 문서에 보존한다. 추가 검사 최초 작성 과정의 입력 Map 문제는 점검용 테스트에서 수정했으며, 위 결과는 수정 후 실행 결과다.

## 검증의 한계와 다음 작업

- 개발 Docker 볼륨을 직접 초기화하거나 두 실제 서버 프로세스를 동시에 기동하지 않았다. 공유 PostgreSQL의 복수 연결로 경쟁 순서를 재현했다.
- 무효 토큰에 대해 `authenticate`가 null을 정상 반환하면 서비스 AOP는 현재 SUCCESS로 기록한다. HTTP 결과와 함께 해석해야 하며, 인증 거부 결과를 서비스 코드에도 표시할지는 별도 보완 대상이다.
- 위 1·2번 결함을 수정하고 3번 테스트를 명확하게 만든 다음 전체 테스트와 추가 회귀 검사를 통과시켜야 완료로 판단할 수 있다.
- 신규 초기화 API는 이번에 구현하지 않았다. 초기화 범위·개발 환경 조건·파일 기반 키 승인·트랜잭션·제거 절차는 별도 계획서에 정의했다.
