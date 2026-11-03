# 관리자 프론트 디자인의 Thymeleaf 이전 계획 — 정식 API·고정 응답 적용안

수정일: 2026-11-02 (Asia/Seoul)

> 최신 보완 계획: [작업자 생성 버튼 및 관리자 화면 실데이터 표시 개선 계획](admin-worker-create-and-real-data-display-fix-plan.md)을 우선 적용한다. 미구현 API의 고정 DTO 반환 보존 범위는 서버 API이며, 관리자 화면은 실제 저장 데이터 또는 빈 상태만 표시한다.

> 후속 요구사항: 공개 작업자 가입과 화면별 독립 샘플 데이터에 관한 내용은 [작업자 생성 권한 및 관리자 화면 데이터 정리 계획](admin-account-and-display-data-cleanup-plan.md)을 우선 적용한다. 기존 미구현 도메인 API의 고정 DTO 반환은 유지한다.

## 1. 변경된 목표와 적용 원칙

**원본의 모든 화면·메뉴·카드·표·모달·입력 폼을 Thymeleaf로 이전한다. 필요한 API는 향후 실제 서비스에서도 사용할 정식 도메인 경로와 요청·응답 계약으로 선언하고, 아직 없는 비즈니스 로직 대신 고정 DTO를 바로 반환한다.**

더미 구현 여부 때문에 URL, 컨트롤러 매핑, HTTP 메서드, ID 타입, 응답 형식을 임시로 바꾸지 않는다. 별도의 UI 전용·demo·mock·stub 경로는 만들지 않는다. 구현 단계만 임시이며 API 계약 자체는 정식 계약이다.

- 기존 관리자 로그인·로그아웃·권한·CSRF와 실제 관리자/모바일 API 동작은 유지한다.
- 이미 같은 메서드·경로·의미의 API가 있으면 그대로 재사용한다. 더미용으로 중복 등록하거나 기존 비즈니스 로직을 상수 반환으로 교체하지 않는다.
- 기존 API에 화면용 정보가 부족하면 기존 필드를 유지하고 필요한 DTO 필드만 추가한다. 새로 추가한 미구현 필드만 고정 값으로 채우며, 기존 실제 값은 유지한다.
- 신규 도메인 API는 정식 경로에 요청 DTO와 반환 DTO를 선언하고, 메서드 안에서 바로 고정 값을 반환한다. 신규 Service/Repository·저장·업무 판정·실제 전송은 구현하지 않는다.
- 미구현 범위와 실제 구현 시 교체할 부분은 컨트롤러·DTO·호출부의 TODO 주석으로 명시한다. 혼동 방지를 위해 원본 디자인에 별도 안내 배너나 개발 설명을 추가하지 않는다.
- 이후 실제 구현은 고정 반환 부분을 Service 호출로 교체한다. API URL·메서드·요청/응답 구조와 화면 호출 주소는 그대로 유지한다.

기존 계획의 미지원 메뉴 제외·카드 축소·확인 단계 제거 방침은 철회한다. 현재 화면에 필요한 추가 정보와 동작은 정식 API의 고정 응답으로 제공한다. 기존 데이터와 신규 고정 응답의 연결 기준은 5절과 6절에 명시한다.

이 계획을 기준으로 API와 Thymeleaf 화면 구현을 진행한다. 실제로 이미 구현된 도메인 기능은 기존 서비스에 연결하고, 추가 도메인 기능은 정식 API 경로와 고정 응답으로 제공한다.

## 2. 조사 기준과 원본 구조

기존 조사 결과와 동일한 소스 스냅샷을 기준으로 계획을 수정했다. 이번 수정에서 원격 저장소의 최신 변경을 다시 조사하지 않았다.

| 항목 | 기준 |
| --- | --- |
| 프론트 저장소 | `mobileProgrammingTeam/admin_interface`, `main` |
| 조사한 프론트 커밋 | `2e76a669c7c45dacea4738e7a7d2529ace435556` |
| 최초 조사 시 서버 HEAD | `6efebebb5132db8014675c6016d9b21399a17138` |
| 프론트 기술 | HTML, CSS, Vanilla JavaScript, ES 모듈, 해시 라우팅 |
| 현재 서버 | Java 17, Maven, Spring Boot 4.1.2, Thymeleaf, Security, JPA, SQLite |
| 조사 범위 | 전체 파일 트리, 공통 HTML/CSS, 모든 화면 모듈, 인증·HTTP·폴링·샘플 저장소, API 제안서와 테스트 |
| 검증 범위 | 소스 정적 검토. 브라우저 비교와 테스트 실행은 구현 단계에서 수행 |

프론트에는 React/Vue나 번들 빌드가 없다. Node 서버는 정적 파일 제공용이므로 Spring 배포에 추가할 필요가 없다. 현재 Spring 프로젝트에도 Thymeleaf와 관리자 화면이 이미 있다.

| 원본 파일 | 역할 | 이전 방법 |
| --- | --- | --- |
| `index.html` | 사이드바·상단바·알림·공통 dialog | Thymeleaf 공통 fragment |
| `css/style.css` | 전체 디자인과 반응형 규칙 | 전체 디자인 규칙을 기존 `admin.css`에 순서대로 적용 |
| `js/pages/*.js` | 화면별 HTML 문자열 | 화면별 Thymeleaf 템플릿 |
| `js/app.js` | 해시 라우팅·렌더링·상호작용 | URL 이동은 MVC, 나머지는 최소 JS |
| `js/components/ui.js` | 표·배지·시각·escape | 템플릿 fragment와 표시 함수 |
| `js/api/*` | 임시 API 경로·응답 처리 | Spring의 정식 도메인 API로 연결 |
| `js/services/auth.js` | 샘플/임시 인증 | 실제 Spring 폼 로그인 |
| `js/services/realtime.js` | 15초 조회 | Thymeleaf fragment 재조회 |
| `js/mock/*` | 브라우저 저장·샘플 상태 변경 | 응답 예시만 서버 상수 DTO로 옮기고 상태 변경 코드는 제외 |

원본의 현재 작업자 상세는 생체신호 그래프 대신 상태 배지와 이벤트 표를 표시한다. README나 남은 차트 CSS만 보고 그래프를 새로 추가하지 않는다. 외부 이미지·폰트 파일은 조사한 트리에 없으며 시스템 폰트, 문자 아이콘, 인라인 SVG를 사용한다.

근거: [화면 틀](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/index.html), [화면 연결 코드](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/js/app.js), [작업자 화면](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/js/pages/workers.js).

## 3. ‘비즈니스 로직 없이 반환’하는 구현 범위

### 3.1 구현하는 것

- 향후 실제 사용할 도메인별 URL, HTTP 메서드, 경로/쿼리 파라미터, 요청 DTO와 반환 DTO.
- 요청 필수 항목·길이·타입 등 기본 형식 검사와 기존 세션·CSRF 검사.
- 이미 구현된 기능에 대한 기존 Service 재사용. 기존 동작의 더미 교체는 하지 않는다.
- 미구현 API/추가 필드의 고정 DTO 반환과 미구현 범위를 밝히는 TODO 주석.
- 같은 데이터를 사용한 최초 Thymeleaf 렌더링, HTML fragment와 필요한 JSON 응답.
- 원본 검색·필터·CSV·dialog 같은 표시 동작. 신규 업무 검색·집계는 고정 결과를 반환한다.

### 3.2 신규 구현에서 제외하는 것

- 새 업무 Service/Repository, Entity, 테이블·열·인덱스와 DB 마이그레이션.
- 상태 전이 검증·저장, 감사 기록 생성, 위험도/생체신호/통신/거리/체류 판정.
- NFC 요청 스케줄·기한 검사·자동 SOS 생성.
- 실제 기기 제어·메시지·푸시·메일·외부 날씨 호출.
- 신규 관리자/공개 가입 API의 실제 계정 생성·권한 변경·계정 중지.
- localStorage·sessionStorage·세션 속성·가변 Map을 이용한 업무 상태 저장.
- 현재 시각·난수로 값을 갱신하는 시뮬레이터.

기존 작업자 생성·비밀번호 초기화·이벤트 처리의 비즈니스 로직은 이미 있으므로 유지한다. 제외 목록은 신규 미구현 기능에만 적용한다.

여러 반환부에서 필요한 상수만 작은 `AdminStubResponses` 클래스에 둘 수 있다. 이 클래스는 불변 응답 정의이며 저장소·상태 머신·교체 가능한 provider 계층으로 만들지 않는다. 한 번 쓰는 응답은 해당 메서드에서 직접 구성한다.

### 3.3 정식 응답과 변경 요청 정책

신규 API도 현재 프로젝트와 같이 **정상 DTO 자체**를 반환하고 오류는 `{code,message}`를 사용한다. 원본 프론트의 `{data,error}` 가정은 호출부에서 현재 프로젝트 계약에 맞춘다. 더미 구현을 구별하려고 `dummy`, `persisted`, `DUMMY_RESPONSE` 같은 전용 필드나 envelope를 계약에 넣지 않는다.

상태 코드도 향후 계약에 맞춘다. 조회·수정 결과는 200, 자원 생성 결과는 201, 본문 없는 성공은 204 등 API별로 정한다. 아직 생성하지 않았다는 이유로 모든 엔드포인트를 임시 200 응답으로 통일하지 않는다. 실제 저장·전송이 없는 점은 주석과 구현 문서에 기록한다.

예: `PATCH /api/admin/events/{eventId}/acknowledge`는 아래와 같은 정식 이벤트 응답 DTO를 반환한다.

```json
{
  "eventId": 1001,
  "workerId": 101,
  "status": "ACKNOWLEDGED",
  "acknowledgedAt": "2026-11-02T00:10:00Z",
  "acknowledgedBy": 1
}
```

위 내용은 관련 필드 발췌이며 실제 응답은 합의한 공통 이벤트 DTO를 사용한다. 다른 필드를 빠뜨린 별도 더미 결과 wrapper는 만들지 않는다.

신규 변경 API는 요청을 수신하고 고정 결과만 반환한다. GET 재조회값이 바뀌지 않아도 이번 단계의 정상 동작이다. 버튼 클릭 직후 응답을 표시할 수 있으나 다른 통계·목록까지 영구 변경된 것처럼 갱신하는 가상 업무 로직은 추가하지 않는다. 확인→해결 등 각 상태의 디자인 검증은 템플릿 테스트 데이터로도 확인한다.

### 3.4 주석 작성 기준

신규 stub 메서드 바로 위/반환 직전에는 다음을 남긴다.

1. 아직 구현하지 않은 조회·검증·저장·전송 범위.
2. 현재 값이 고정 응답이며 다음 GET이나 서버 재시작에 저장되지 않는다는 점.
3. 향후 Service로 교체할 위치와 유지할 정식 계약.

```java
@PatchMapping("/api/admin/events/{eventId}/acknowledge")
public EventDetails acknowledge(@PathVariable long eventId) {
    // TODO: 이벤트 조회, 확인 가능 상태 검증, 확인자/시각 저장 및 감사 기록 구현.
    // 현재는 계약 검증용 고정 응답만 반환한다. DB 상태는 변경하지 않는다.
    // 실제 구현 시 이 반환부를 Service 호출로 교체하고 URL/DTO 계약은 유지한다.
    return new EventDetails(/* 정식 DTO 필드: eventId와 나머지 고정 값 */);
}
```

이는 계획 설명용 의사 코드이며 실제 Java DTO 정의에 맞추어 작성한다. 기존 DTO의 추가 필드에는 `// TODO: 현재 고정 값, 실제 측정/조회 연동 필요`처럼 필드 단위 주석을 남긴다. 화면 호출부에도 필요한 경우 해당 API의 저장/전송이 아직 미구현임을 짧게 적는다.

## 4. 원본 디자인 보존 범위

| 화면 | 유지할 원본 요소 | 연결할 데이터/API |
| --- | --- | --- |
| 로그인 | 로그인 카드와 입력 | 실제 Spring 인증. 계정 생성 링크 없음 |
| 대시보드 | 카드 4개, 우선 알림 배너, 위험 목록, 구역별 현황, 작업자 표 | 동일 세트의 대시보드·작업자·이벤트 더미 |
| 긴급 알림·대응 | 신규/요청·우선 알림·대응 중 카드, 대응 표 | 더미 이벤트·작업자·설정 |
| 작업자 목록 | 이름/ID 검색, 안전 상태 필터, 원본 열·배지 | 기존 기본정보 + 신규 상태 API 고정 응답 |
| 작업자 상세 | 아바타·상태·생체신호·통신·NFC·SOS·이벤트 | 기존 상세/이벤트 + 신규 상태·안전확인/SOS 고정 응답 |
| 현장 구역 | 구역 카드, 접근 경고·장기 체류 대응 폼 | 고정 DTO. 현장 지도는 등록 전 빈 상태 표시 |
| 구역 상세 | 구역명·작업자 수·상태 표 | 더미 구역 상세 |
| 기기·통신 | 온라인/지연/오프라인 카드, 기기·시각·상세 링크 | 미리 정한 통신 상태 더미 |
| 날씨 | 메시지 입력, 온도·습도·관측 시각, 메시지 이력 | 더미 날씨·주의 메시지 |
| 이벤트 이력 | 원본 상태/유형 필터, 위험도·상세 열 | 기존 실제 이벤트 + 미구현 필드 고정 응답 |
| 이벤트 상세 | 작업자·유형·위험도·구역·시각·메모, 상태별 버튼 | 기존 실제 상세 + 신규 확인/해결 고정 응답 |
| 처리 기록·보고서 | 통계 카드, 검색·처리 필터, 표, CSV | 더미 감사 기록 |
| 설정 | 우선 위험도·지연/오프라인 기준 입력·저장 | 더미 설정 GET/PATCH |
| 관리자 관리 | 관리자 목록·역할·활성, 추가 폼, 작업자 추가 폼 | 신규 관리자 고정 응답 + 기존 실제 작업자 생성 |

로그인 자격증명 라벨은 현재 서버의 ‘관리자 ID’로 맞춘다. 이는 인증 계약을 유지하기 위한 문구/입력 타입 조정이며 카드·버튼·간격 디자인은 유지한다. ‘샘플 관리자 입장’처럼 인증 없이 진입하는 동작은 옮기지 않는다. 개발용 샘플 초기화 버튼은 별도 초기화 API를 만들지 않고 입력 초기화와 고정 데이터 재조회에 연결한다.

### 보존할 CSS 기준

224px 남색 사이드바, 우측 작업 영역, 상단 설정/관리자 관리/로그아웃, 카드 4열, 우선 알림 배너, 2열 패널, 둥근 표/배지/dialog와 활성 메뉴를 보존한다. 등록되지 않은 현장 지도를 예시 SVG로 채우지 않는다. 1100px·760px·520px의 반응형 규칙을 유지한다.

CSS는 기본 규칙 뒤에 관제 규칙이 덮어쓰는 구조다. 이전하면서 임의 정렬·통합·정리를 하지 않는다. 기존 계획처럼 구역·날씨 스타일을 제외하지 않는다.

근거: [CSS](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/css/style.css), [대시보드](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/js/pages/dashboard.js), [전체 API 호출부](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/js/api/index.js).

## 5. 정식 API 경로와 재사용 계획

### 5.1 경로·계약 원칙

관리자 업무는 `/api/admin/**`, 작업자 인증은 `/api/worker/auth/**`에 둔다. HTML 페이지는 기존 `/admin/**`를 유지한다. 작업자 계정 생성은 관리자 세션을 사용하는 `/api/admin/workers`만 허용한다.

| 구분 | 처리 |
| --- | --- |
| 기존 API와 메서드·경로·의미가 일치 | 기존 매핑/로직/응답 필드를 재사용 |
| 기존 응답에 추가 정보만 부족 | 기존 DTO를 호환되게 확장하고 미구현 필드만 고정 반환 |
| 현재 없는 업무/리소스 | 정식 도메인 경로에 신규 메서드를 선언하고 고정 DTO 반환 |
| 실제 구현 전환 | 매핑과 계약은 유지하고 해당 반환부만 Service 호출로 교체 |

기존 컨트롤러와 동일한 메서드·경로를 가진 신규 컨트롤러는 등록하지 않는다. 요청 DTO가 다르다는 이유로 같은 POST URL을 두 번 선언하지 않는다. 이미 구현된 부분을 호출하는 작은 DTO 변환은 허용하되 새로운 업무 규칙을 넣지 않는다.

Long 식별자를 쓰는 작업자·이벤트·관리자는 신규 응답에도 같은 타입을 사용한다. 향후 연동 때 문자열 ID를 숫자로 바꾸는 작업이 생기지 않도록 `demo-worker-001` 같은 임시 ID 규약은 사용하지 않는다.

### 5.2 기존 API 재사용·확장

| 메서드·정식 경로 | 기존 동작 | 이번 적용 |
| --- | --- | --- |
| GET/POST `/admin/login`, POST `/admin/logout` | 실제 인증/세션 종료 | 그대로 유지 |
| GET `/api/admin/workers` | 실제 페이지 목록 | 기존 필드·페이지 계약 유지 |
| GET `/api/admin/workers/{workerId}` | 실제 작업자 상세 | 기본정보 유지, 필요하면 확장된 상태 DTO와 화면에서 조합 |
| POST `/api/admin/workers` | 실제 작업자 생성 | 기존 `workerName/sex/age`와 임시 비밀번호 반환 유지. 중복 stub 생성 금지 |
| POST `/api/admin/workers/{workerId}/reset-password` | 실제 초기화 | 그대로 유지 |
| GET `/api/admin/events` | 실제 이벤트 페이지·필터 | 기존 필드 유지, 부족한 메타데이터만 고정 값으로 확장 |
| GET `/api/admin/events/{eventId}` | 실제 이벤트 상세 | 기존 데이터 유지, 위험도·확인 정보 등 미구현 필드만 고정 보충 |
| PATCH `/api/admin/events/{eventId}/process` | 실제 1회 완료 처리 | 기존 계약·로직·409 동작 유지 |
| 기존 `/api/worker/**` | 모바일 인증·이벤트·프로필 등 | 그대로 유지 |

기존 실제 값과 추가 고정 값은 필드 주석으로 구분한다. 예를 들어 실제 `processedAt`을 고정 값으로 덮어쓰거나, 기존 작업자 이름/ID를 샘플 값으로 교체하지 않는다.

원본 관리자 화면의 작업자 추가 폼은 기존 생성 API의 필수 이름·성별·나이를 받을 수 있게 필요한 입력을 추가하되 공통 디자인을 유지한다. 구역 선택값 등 아직 처리하지 않는 추가 항목은 DTO 주석으로 미구현을 표시하며 실제 구역 배정은 하지 않는다. 기존 생성 요청을 `{name,zoneId}` 전용 stub으로 바꾸지 않는다.

### 5.3 신규 조회 API

다음은 향후에도 같은 용도로 사용할 정식 경로다. 현재는 고정 응답을 바로 반환한다.

| 메서드·정식 경로 | 정식 반환 계약의 역할 |
| --- | --- |
| GET `/api/admin/dashboard` | 카드 4개·우선 알림·구역 현황·최근 이벤트 집계 |
| GET `/api/admin/workers/status` | 작업자별 현재 안전·생체·통신 상태 |
| GET `/api/admin/workers/{workerId}/status` | 해당 작업자의 생체·기기·NFC·구역 상태 상세 |
| GET `/api/admin/zones` | 구역별 작업자 현황 |
| GET `/api/admin/zones/{zoneId}` | 구역 상세·소속 작업자 |
| GET `/api/admin/restricted-areas/warnings` | 접근 자동 경고 기록 |
| GET `/api/admin/restricted-areas/responses` | 관리자 대응 메시지 기록 |
| GET `/api/admin/weather/current` | 현재 날씨·관측 시각·출처 |
| GET `/api/admin/weather/notices` | 주의 메시지 이력 |
| GET `/api/admin/audit/events` | 이벤트 확인/완료 처리 기록 |
| GET `/api/admin/settings` | 우선 위험도·통신 기준 설정 |
| GET `/api/admin/admins` | 관리자 목록·역할·활성 상태 |

작업자 기본정보와 현재 상태는 서로 다른 리소스이므로 기존 상세와 신규 status API를 조합한다. `/workers/status`가 `/workers/{workerId}`의 숫자 변환 경로와 충돌하지 않는지 MVC 매핑을 검증한다.

목록 계약은 기존 프로젝트의 `PageResponse<T>` 형태를 기본으로 한다. 원본 배열 사용부는 `items`를 읽도록 조정한다. 신규 API는 `page/size` 등 향후 필요한 조회 파라미터를 형식상 받고 정해진 페이지 응답을 반환한다. 실제 페이지 조회·업무 필터·집계 로직은 구현하지 않는다. 단일 설정·날씨·대시보드는 객체 DTO를 반환한다.

### 5.4 신규 변경 API

| 메서드·정식 경로 | 요청/정식 응답 | 이번 구현 |
| --- | --- | --- |
| PATCH `/api/admin/events/{eventId}/acknowledge` | 확인 요청 → 이벤트 DTO | 고정 확인 결과 |
| PATCH `/api/admin/events/{eventId}/resolve` | `adminMemo` → 이벤트 DTO | 고정 해결 결과, 기존 process를 호출하지 않음 |
| POST `/api/admin/workers/{workerId}/safety-check` | 요청 → 안전확인 DTO | 고정 요청 결과 |
| POST `/api/admin/workers/{workerId}/sos` | 요청 → SOS 요청 DTO | 고정 요청 결과 |
| POST `/api/admin/workers/{workerId}/restricted-response` | `message` → 대응 기록 DTO | 고정 대응 결과 |
| POST `/api/admin/weather/notices` | `message` → 메시지 DTO | 고정 메시지 결과 |
| PATCH `/api/admin/settings` | 기준 설정 → 설정 DTO | 고정 설정 결과 |
| POST `/api/admin/admins` | 관리자 정보 → 관리자 DTO | 고정 생성 결과 |
| PATCH `/api/admin/admins/{adminId}/role` | `role` → 관리자 DTO | 고정 역할 결과 |
| PATCH `/api/admin/admins/{adminId}/enabled` | `enabled` → 관리자 DTO | 고정 활성 결과 |

관리자 활성 변경은 현재 상태를 반전시키는 임시 toggle 대신 목표 `enabled` 값을 명시한다. 원본 버튼의 모양은 유지하고 호출 계약만 맞춘다.

`acknowledge/resolve`는 향후 다단계 처리용 정식 경로이며 기존 단일 `process`는 호환 유지한다. 이번에는 신규 resolve를 기존 실제 process로 연결하지 않는다. 실제 상태 전이·재처리 검증·확인/완료 저장·두 API의 상태 연동은 주석으로 남긴다.

신규 DTO의 역할·이벤트 상태·유형은 향후 업무 의미를 갖는 값으로 정의하되 Entity enum/DB 제약에 아직 반영하지 않는다. 추가된 값의 실제 판정·저장은 TODO로 남긴다.

### 5.5 최초 렌더링과 호출부 연결

기존 기능은 `SafetyService`와 기존 DTO를 사용한다. 신규 기능은 컨트롤러에서 사용하는 것과 동일한 고정 응답 정의로 Model을 채운다. MVC에서 자기 서버 REST API를 HTTP로 호출하지 않는다.

프론트의 API 주소는 처음부터 위 정식 경로를 사용한다. 기존 응답은 실제 값, 신규 응답/추가 필드는 상수라는 점을 코드 주석으로 표시한다. 원본 화면의 표시용 이름 차이는 템플릿/DTO 변환에서 맞추고, 기존 `DANGER_ZONE_APPROACH`를 실제 진입 이벤트로 오표기하는 식의 의미 변경은 하지 않는다.

서버가 아직 지원하지 않는 원본 필터 선택값은 정식 조회 DTO에 형식상 받을 수 있으나 신규 업무 조회를 구현하지 않는다. 기존 지원 필터는 기존 조회를 유지하고, 미구현 필터의 반환/무반영 범위는 TODO로 명시한다. 현재 페이지를 검색하고 전체 데이터 검색이라고 표시하지 않는다.

## 6. 응답 DTO와 고정 값 설계

기존 기본정보·이벤트 필드는 실제 데이터를 사용한다. 신규 API와 추가 미구현 필드는 아래 정식 DTO 구조에 고정 값을 채워 반환한다. 실제 값/상수의 출처는 필드와 반환부 주석으로 구분한다.

| DTO | 필요한 주요 필드 |
| --- | --- |
| WorkerStatus | workerId, status, monitoringStatus, connectionStatus, connectionHealth, zone, lastSeenAt |
| WorkerVitals | heartRateStatus, spo2Status, measuredAt |
| WorkerDevice | type, lastSeenAt, connectionHealth |
| WorkerStatusDetails | WorkerStatus 필드, devices, vitals, safetyCheck, proximity |
| SafetyCheck | status, tagId, intervalMinutes, requestedAt, dueAt, lastConfirmedAt, nextRequestAt |
| Proximity | beaconId, restrictedAreaId, distanceMeters, thresholdMeters, measuredAt, insideRestricted, insideSince, status, dwellMinutes |
| EventDetails 확장 | 기존 eventId/workerId/eventType/time/receivedAt/processedAt/processedBy/adminMemo 유지, severity/status/zone/acknowledgedAt/acknowledgedBy 등 추가 |
| Dashboard | total, normal, danger, offline, pending, urgentCount, newCount, acknowledgedCount, recentEvents, zones, workers |
| AuditRecord | eventId, workerId, action, at, actor, adminMemo |
| Weather/Notice | 지역·온도·습도·조건·출처·관측 시각 / 작성자·내용·시각 |
| Admin/Settings | adminId, 이름·로그인 식별 정보·role·enabled / 통신 기준·우선 위험도 |

신규 DTO 명칭은 리소스/업무 의미로 정한다. 계약에 UiDummy/MockResult 같은 구현 단계 이름을 넣지 않는다. 기존 DTO 확장은 기존 필드를 삭제·재명명하지 않고 클라이언트 호환성을 검증한다.

### 고정 응답 규칙

- 작업자·이벤트·관리자 ID는 기존과 같은 Long이며 고정 예시도 양의 정수로 둔다.
- 상세 URL이 ID를 받으면 DTO의 ID에는 경로 값을 그대로 사용하고, 미구현 상태 등 나머지 필드를 고정 값으로 반환할 수 있다. ID 반영은 요청·응답 연결이며 실제 존재/권한 조회는 하지 않는다.
- 고정 목록·상세·구역·감사 기록의 예시 ID/이름은 서로 맞춘다. 고정 ID가 실제 DB에 존재하거나 소속 관계가 검증되었다는 의미는 없으며 TODO에 적는다.
- 기존 조회값을 포함하는 화면에서는 실제 ID·이름·처리 결과를 덮어쓰지 않는다. 실제 이벤트 수신/처리 시각을 통신/생체신호 측정 시각으로 대체하지 않는다.
- 신규 대시보드의 카드 4개와 관련 목록은 같은 고정 예시를 설명하도록 수치를 맞추되 기존 DB 집계와 같다고 보장하지 않는다. 대시보드 집계와 실제 작업자/이벤트 목록은 아직 연동 전임을 반환부에 주석으로 명시한다.
- NORMAL/WARNING/DANGER/OFFLINE, 각 통신·생체 배지, REQUEST/NEW/ACKNOWLEDGED/RESOLVED를 표시할 테스트용 고정 DTO를 준비한다.
- 실제 이벤트에 미구현 status를 추가할 때 기존 processedAt과 모순되는 임의값으로 고정하지 않는다. 기존 처리 여부의 단순 표시 변환은 허용하고, 아직 저장할 수 없는 확인/요청 상태는 신규 API 반환 예시와 템플릿 테스트에서 검증한다. 실제 상태 전이는 구현하지 않는다.
- NFC·구역·날씨·관리자·감사 데이터는 결과를 미리 적는다. 원본 샘플 저장소의 자동 판정/생성 코드는 옮기지 않는다.
- 날짜는 UTC ISO 8601 고정 값이며 화면에서 Asia/Seoul로 표시한다.
- 고정 시각의 경과 때문에 통신/체류 상태가 바뀌지 않게 판정 결과도 고정 제공한다. 화면은 그 결과를 표시하고 Date.now()로 업무 상태를 재판정하지 않는다.
- 필수 응답을 빈 값만으로 채워 원본 화면을 축소하지 않는다. 풍부한 고정 값으로 원본 각 영역을 렌더링한다.

고정 데이터를 실제 DB에 초기 주입하지 않는다. 응답 DTO 구성 외에 신규 위험도 계산·집계·상태 변경 로직을 만들지 않는다.

## 7. Thymeleaf와 Spring 구성

### 7.1 요청 흐름

```text
실제 관리자 로그인
  → 기존 Spring Security 세션

GET /admin/workers, /admin/events, 상세 등
  → AdminWebController
  → 기존 기능: SafetyService의 실제 데이터
  → 추가 정보: 신규 API와 동일한 고정 응답 정의
  → Thymeleaf 전체 페이지

GET /admin/dashboard, /admin/zones 등 신규 기능 화면
  → AdminWebController
  → 고정 응답 정의
  → Thymeleaf 전체 페이지

GET /admin/...?...&fragment=content
  → 동일한 데이터 구성
  → 동일 템플릿의 HTML fragment

PATCH /api/admin/events/{eventId}/acknowledge 등 신규 API
  → 기존 세션 + CSRF 검사
  → 정식 업무 컨트롤러의 해당 메서드
  → TODO 주석 + 고정 DTO 바로 반환
```

MVC가 자기 서버 REST API를 HTTP로 호출하지 않는다. 기존 Service를 재사용하고 신규 고정 응답은 작은 불변 정의를 공유한다. 향후 Service 도입 시 같은 계약의 실제 데이터를 전달하도록 반환부를 교체한다.

### 7.2 파일 배치안

```text
src/main/java/.../
  controller/web/AdminWebController.java
  controller/api/AdminApiController.java      # 기존 매핑 재사용, 관련 정식 API 추가
  controller/api/WorkerController.java        # 기존 작업자 인증 API 유지
  dto/ApiModels.java                          # 기존 DTO 호환 확장, 신규 업무 DTO
  support/AdminStubResponses.java             # 필요한 고정 응답만 공유
  exception/ApiExceptionHandler.java          # 기존 오류 형식 재사용
  exception/AdminWebExceptionHandler.java
  config/SecurityConfig.java                 # 가입 경로에 필요한 범위만 조정
src/main/resources/templates/admin/
  fragments/common.html
  fragments/tables.html
  login.html
  dashboard.html
  workers.html
  worker-detail.html
  alerts.html
  events.html
  event-detail.html
  zones.html
  zone-detail.html
  connectivity.html
  weather.html
  reports.html
  settings.html
  admins.html
  error.html
src/main/resources/static/
  css/admin.css
  js/admin.js
```

컨트롤러는 기존 프로젝트 구조에 맞춰 업무별로 배치한다. 메서드가 커져 분리가 필요하면 WeatherController/ZoneController처럼 실제 업무 이름을 사용한다. 더미 구현이라는 이유로 별도 UI 컨트롤러나 별도 예외 envelope를 만들지 않는다. 공통 고정 응답 클래스는 나중에 제거할 내부 구현이며 공개 API 계약에 노출하지 않는다.

신규 미구현 기능을 위해 SafetyService, Repository, Entity, schema.sql에 업무 구현을 추가하지 않는다. 기존 서비스는 실제 동작을 유지한다. README에는 엔드포인트별 구현 완료/고정 반환/TODO 상태를 기록하고 화면에는 원본에 없는 개발 설명을 추가하지 않는다.

### 7.3 화면 URL

| 경로 | 템플릿/역할 |
| --- | --- |
| GET `/admin/login` | 실제 로그인 |
| GET `/admin/register` | 기존 주소 호환용. 비로그인 시 로그인, 로그인 시 작업자 관리로 이동 |
| GET `/admin/dashboard` | 대시보드 |
| GET `/admin/alerts` | 긴급 알림·대응 |
| GET `/admin/workers` | 작업자 목록 |
| GET `/admin/workers/{workerId}` | 작업자 상세 |
| GET `/admin/events` | 이벤트 이력 |
| GET `/admin/events/{eventId}/detail` | 상세 dialog fragment |
| GET `/admin/zones`, `/admin/zones/{zoneId}` | 구역 목록/상세 |
| GET `/admin/connectivity` | 기기·통신 |
| GET `/admin/weather` | 날씨 |
| GET `/admin/reports` | 처리 기록·보고서 |
| GET `/admin/settings` | 설정 |
| GET `/admin/admins` | 관리자 관리 |

작업자 계정 생성은 관리자 로그인 후 작업자 관리 화면에서 실제 `POST /api/admin/workers`를 호출한다. `/api/worker/auth/register` 공개 가입 API는 제공하지 않는다.

원본 해시 링크를 MVC 경로로 바꾸고 `th:href`, `th:action`, `th:src`를 사용한다. HTML fragment 이름은 허용된 값만 받는다.

### 7.4 표시·갱신·보안

- 최초 제목·통계·표·모달은 Thymeleaf로 렌더링하고 JS에 동일 HTML 문자열 템플릿을 중복 작성하지 않는다.
- 기존 목록은 기존 서버 페이지네이션·필터를 유지한다. 신규 stub 조회는 정식 파라미터를 받되 고정 결과만 반환한다. 필터 실제 적용이 미구현이면 호출부에 TODO를 남긴다.
- 기존 이벤트/미처리 목록의 5초 갱신은 유지한다. 신규 미구현 화면의 15초 갱신은 같은 고정 값 재조회이며 통신·위험 판정이나 알림 생성을 수행하지 않는다.
- fragment 재조회 시 결과 영역만 교체하고 입력·포커스·모달을 보존한다. 화면 이동·로그아웃 시 타이머/진행 요청을 정리하고 늦은 응답을 무시한다.
- 실제 변경과 신규 고정 반환을 같은 일반화 코드로 섞어 임의 API를 호출하지 않는다. 기존 생성/초기화/process는 실제 기능, 신규 acknowledge/resolve/설정/메시지는 미구현이라는 점을 해당 메서드와 호출부 주석으로 남긴다.
- 기존 로그인·로그아웃·관리자 변경 fetch의 CSRF와 same-origin 쿠키를 유지한다. 작업자 생성은 `/api/admin/**` 관리자 인증 체인과 CSRF 검사를 통과해야 한다.
- JSON은 현재 프로젝트 DTO/오류 형식을 사용한다. 401 로그인 이동, 403 권한/CSRF 안내, 기존 process의 409 재조회 동작을 유지한다.
- fragment가 로그인 화면으로 리다이렉트되면 표 안에 삽입하지 않고 로그인으로 이동한다.
- `th:text`와 DOM `textContent`로 외부 문자열을 출력한다. 비밀번호를 Model·저장소·URL·로그·응답에 남기지 않는다.
- 고정 관리자 역할/상태는 실제 세션 권한을 변경하지 않는다. 서버 권한 검증은 현재 ADMIN 정책을 유지한다.
- dialog 제목·Esc 닫기·포커스 복귀·aria-live를 유지한다.

CSV는 감사 조회 응답의 기록을 사용해 원본처럼 브라우저에서 생성한다. 현재 감사 조회는 고정 기록이며 신규 집계/저장은 구현하지 않는다. UTF-8 BOM·따옴표 처리·수식 삽입 방지를 유지한다.

## 8. 단계별 실행 계획

| 단계 | 작업 | 산출물 | 검증 기준 |
| --- | --- | --- | --- |
| 0 | 기존 API와 원본 요구 대조 | 정식 경로·메서드·입력·출력·실제/stub 목록 | 동일 경로 중복 없음, 향후 URL 변경 불필요 |
| 1 | DTO·고정 응답 정의 | 기존 DTO 호환 확장, 신규 업무 DTO | ID 타입·기존 필드·응답 형식 유지 |
| 2 | 신규 API 선언 | 정식 컨트롤러 메서드, 고정 return, TODO | 신규 비즈니스 로직/저장/전송 없음 |
| 3 | 공통 디자인 | CSS·fragment·로그인·가입 | 원본 레이아웃·메뉴·반응형 유지 |
| 4 | 핵심 화면 연결 | 작업자·이벤트·대응·대시보드·상세 | 기존 기능은 실제 데이터, 추가 기능은 고정 응답 |
| 5 | 전체 화면 이전 | 구역·통신·날씨·기록·설정·관리자 | 빠진 메뉴·카드·입력·SVG 없음 |
| 6 | 상호작용 | 변경 요청·검색·갱신·CSV | 정식 경로 호출, stub 무저장, 실제 기능 보존 |
| 7 | 회귀·시각·배포 확인 | 테스트·캡처·README 구현 현황 | 9절 완료 기준 충족 |

비즈니스 로직 미구현 때문에 URL에 임시 접두어를 추가하거나 정상 응답 구조를 바꾸지 않는다. 어떤 API/필드가 미구현인지 주석과 구현 현황 문서로 검토한다.

## 9. 검증 계획과 완료 조건

### 9.1 필요한 자동 검증

구현 전후 `.\\mvnw.cmd clean verify`를 실행한다. 기존 모바일 Bearer 분리, 관리자 인증·CSRF, 실제 생성/초기화/처리, 처리 메모·409 충돌, 토큰 폐기·탈퇴 이력·정렬/페이지 검증을 유지한다.

추가 검증은 다음에 집중한다.

1. 모든 화면·상세·fragment가 필요한 실제/고정 Model을 받아 렌더링된다.
2. 신규 엔드포인트는 문서의 정식 경로에만 있고 기존 메서드·경로와 중복되지 않는다.
3. 기존 DTO 필드·ID 타입·정상/오류 형식이 유지되며 신규 필드가 호환되게 추가된다.
4. 신규 변경 API 호출 후 실제 계정·이벤트·토큰·권한·DB 상태가 바뀌지 않는다.
5. 신규 stub은 업무 Service/Repository·외부 API를 호출하지 않으며 GET 재조회가 고정 결과를 반환한다.
6. 기존 실제 API는 계속 저장/검증하고 원래 오류 계약을 유지한다.
7. 관리자 신규 경로의 세션·CSRF 정책과 공개 가입의 경로별 인증 정책이 의도대로 적용된다.
8. Long ID·상세 URL·추가 상태 DTO의 참조를 확인하고 기존 실데이터 필드를 고정 값으로 덮어쓰지 않는다.
9. 이름·메모·메시지가 안전하게 출력되고 비밀번호가 로그/응답에 남지 않는다.
10. stub/TODO 주석이 해당 메서드·추가 필드에 있고 실제 전환 시 URL·DTO 교체가 필요하지 않다.

무저장 stub에 상태 전이·저장 지속성 테스트를 통과시키기 위한 가상 저장소를 추가하지 않는다. 기존 실제 로직의 테스트와 신규 무저장 계약의 테스트를 구분한다.

### 9.2 브라우저 검증

- 원본과 동일한 표시 상태를 가진 비교용 데이터로 메뉴·카드 개수·표 열·색상·간격·dialog·SVG를 비교한다.
- 1440px·1024px·760px 경계·390px·최소 320px에서 반응형 배치를 확인한다.
- 요청/신규/확인/해결별 표시 분기는 실제 가능한 상태와 템플릿 테스트 fixture를 사용해 확인한다.
- 신규 확인/해결·설정·역할·SOS·메시지 요청은 정식 URL로 전송되지만 재조회값은 저장되지 않는지 확인한다.
- 기존 작업자 생성·초기화·process는 계속 실제 동작하는지 확인한다.
- 고정 측정/통신/체류 상태를 화면이 임의로 재판정하지 않는지 확인한다.
- 목록 갱신 중 입력·포커스·열린 모달을 유지하고 인증 만료·통신 오류·늦은 응답을 처리한다.
- 실제 업무 호출 경로와 주석의 미구현 범위가 일치하는지 검토한다.
- CSV 한글·줄바꿈·따옴표·수식 시작 문자의 처리를 확인한다.

### 9.3 완료 조건

- 원본의 모든 메뉴와 화면 요소를 Thymeleaf로 제공한다.
- 필요한 API가 향후 사용할 정식 도메인 URL·메서드·DTO 계약으로 선언되어 있다.
- 기존 구현은 실제 동작을 유지하고, 신규 미구현 기능만 고정 return과 TODO로 남는다.
- 별도 임시 API namespace·전용 응답 wrapper·비즈니스 상태 저장을 추가하지 않는다.
- 신규 업무 Service/DB/상태 전이/실제 전송을 구현하지 않는다.
- 이후 실제 구현은 반환부 교체로 진행할 수 있고 화면의 API URL을 변경하지 않는다.
- 기존 모바일 계약·DB 스키마·배포를 유지하며 Maven JAR/Docker에서 별도 Node 서버 없이 실행된다.

## 10. 이후 실제 비즈니스 로직 연결

신규 미구현 기능의 Service/DB/외부 연동은 이번 범위 밖이다. 정식 URL·메서드·입력·출력은 이번에 두고 실제 구현 시 해당 메서드의 고정 반환부만 교체한다. 다른 경로로 이사하거나 템플릿의 호출 URL을 다시 바꾸는 계획은 두지 않는다.

각 TODO를 기준으로 조회·권한·상태 전이·저장·감사 기록·기기 전달을 구현하고, 상수 추가 필드는 실제 조회값으로 바꾼다. 주석에 남긴 무저장/미전송 제한은 실제 검증을 마친 뒤 제거한다. 기존 process와 신규 acknowledge/resolve의 연동 정책도 이때 구현한다.

Long ID 타입, 기존 DTO 필드와 프로젝트 응답 형식은 유지한다. Entity와 SQLite CHECK 제약의 확장이 필요해지는 시점에 별도 마이그레이션을 설계하며, 고정 예시 값을 실제 DB 초기 데이터로 주입하지 않는다.

## 11. 참고 소스

- [조사한 프론트 전체](https://github.com/mobileProgrammingTeam/admin_interface/tree/2e76a669c7c45dacea4738e7a7d2529ace435556)
- [프론트 API 제안](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/docs/api-contract.md)
- [현재 상태 API 제안](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/worker-status-api.md)
- [샘플 저장소: 응답 형태만 참고](https://github.com/mobileProgrammingTeam/admin_interface/blob/2e76a669c7c45dacea4738e7a7d2529ace435556/js/mock/repository.js)
- 현재 서버: [Maven](../pom.xml), [README](../README.md), [MVC](../src/main/java/org/example/workersafety_server/controller/web/AdminWebController.java), [관리자 API](../src/main/java/org/example/workersafety_server/controller/api/AdminApiController.java), [보안](../src/main/java/org/example/workersafety_server/config/SecurityConfig.java), [서비스](../src/main/java/org/example/workersafety_server/service/SafetyService.java), [DTO](../src/main/java/org/example/workersafety_server/dto/ApiModels.java), [스키마](../src/main/resources/schema.sql).
