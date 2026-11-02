import getpass
import json
import re
import secrets
import string
import sys
from datetime import datetime, timezone
from html.parser import HTMLParser
from urllib.parse import parse_qsl, urlencode

import httpx


TIMEOUT = 10.0
HIDDEN = "[숨김]"
SENSITIVE_WORDS = ("password", "token", "csrf", "cookie", "authorization")


class CheckFailed(Exception):
    pass


def redact_json(value):
    if isinstance(value, dict):
        return {
            key: HIDDEN if any(word in key.lower() for word in SENSITIVE_WORDS)
            else redact_json(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_json(item) for item in value]
    return value


def safe_body(raw_body, content_type):
    if not raw_body:
        return "(빈 본문)"
    text = raw_body.decode("utf-8", errors="replace")
    if "json" in content_type.lower():
        try:
            return json.dumps(
                redact_json(json.loads(text)), ensure_ascii=False, indent=2
            )
        except ValueError:
            pass
    if "application/x-www-form-urlencoded" in content_type.lower():
        fields = parse_qsl(text, keep_blank_values=True)
        return urlencode([
            (key, HIDDEN if any(word in key.lower() for word in SENSITIVE_WORDS) else value)
            for key, value in fields
        ])
    text = re.sub(
        r'(?is)(name=["\'](?:_csrf|csrf-token)["\'][^>]*?\b(?:value|content)=["\'])[^"\']*',
        rf"\g<1>{HIDDEN}", text,
    )
    text = re.sub(
        r'(?is)(<input\b[^>]*\bvalue=["\'][^"\']*["\'][^>]*\bname=["\']_csrf["\'][^>]*\bvalue=["\'])[^"\']*',
        rf"\g<1>{HIDDEN}", text,
    )
    return text or "(빈 본문)"


def print_failure(method, path, request, response, reason):
    print(f"{method} {path} KO")
    print(f"이유: {reason}")
    print("\n[보낸 요청]")
    if request is None:
        print("요청을 전송하지 못했습니다.")
    else:
        print(f"URL: {request.url}")
        print(f"메서드: {request.method}")
        print("헤더:")
        for name, value in request.headers.multi_items():
            if any(word in name.lower() for word in SENSITIVE_WORDS):
                value = HIDDEN
            print(f"  {name}: {value}")
        print("본문:")
        print(safe_body(request.content, request.headers.get("content-type", "")))

    print("\n[받은 응답]")
    if response is None:
        print("수신 없음")
    else:
        print(f"HTTP 상태: {response.status_code}")
        print("헤더:")
        for name, value in response.headers.multi_items():
            if any(word in name.lower() for word in SENSITIVE_WORDS):
                value = HIDDEN
            print(f"  {name}: {value}")
        print("본문:")
        print(safe_body(response.content, response.headers.get("content-type", "")))


def request_and_check(client, method, path, expected_status, *, base_url,
                      params=None, headers=None, json_body=None, form=None,
                      check=None):
    request = client.build_request(
        method,
        base_url.rstrip("/") + path,
        params=params,
        headers=headers,
        json=json_body,
        data=form,
        timeout=TIMEOUT,
    )
    try:
        response = client.send(request, follow_redirects=False)
    except httpx.HTTPError as error:
        print_failure(method, path, request, None, f"{type(error).__name__}: {error}")
        raise CheckFailed from error

    try:
        if response.status_code != expected_status:
            raise ValueError(
                f"기대 HTTP 상태 {expected_status} / 실제 {response.status_code}"
            )
        parsed = None
        if response.content:
            try:
                parsed = response.json()
            except ValueError:
                parsed = response.text
        if check is not None:
            check(response, parsed)
    except (ValueError, TypeError, KeyError) as error:
        print_failure(method, path, request, response, str(error))
        raise CheckFailed from error

    print(f"{method} {path} OK")
    return response, parsed


class CsrfParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.form_token = None
        self.header_token = None
        self.header_name = None

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "input" and attributes.get("name") == "_csrf":
            self.form_token = attributes.get("value")
        if tag == "meta" and attributes.get("name") == "csrf-token":
            self.header_token = attributes.get("content")
        if tag == "meta" and attributes.get("name") == "csrf-header":
            self.header_name = attributes.get("content")


def require_object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} 응답이 JSON 객체가 아닙니다.")
    return value


def require_fields(value, fields, label):
    body = require_object(value, label)
    for name in fields:
        if name not in body:
            raise ValueError(f"{label} 응답에 필수 필드 {name}이 없습니다.")
    return body


def require_positive_id(body, field, label):
    value = body.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"{label} 응답의 {field}가 올바른 양의 정수가 아닙니다.")


def check_status(response, data):
    body = require_fields(data, ("status",), "상태 확인")
    if body["status"] != "UP":
        raise ValueError("status 값이 UP이 아닙니다.")


def check_csrf_page(response, data, csrf):
    csrf.feed(response.text)
    if not csrf.header_token or not csrf.header_name:
        raise ValueError("관리자 페이지에서 CSRF 토큰 또는 헤더명을 찾지 못했습니다.")


def check_csrf_form(response, csrf):
    csrf.feed(response.text)
    if not csrf.form_token:
        raise ValueError("로그인 폼에서 CSRF 토큰을 찾지 못했습니다.")


def check_worker_created(response, data):
    body = require_fields(data, ("workerId", "temporaryPassword"), "작업자 생성")
    require_positive_id(body, "workerId", "작업자 생성")
    if not isinstance(body["temporaryPassword"], str) or not body["temporaryPassword"]:
        raise ValueError("작업자 생성 응답의 임시 비밀번호가 비어 있습니다.")


def check_worker_list(response, data):
    body = require_fields(data, ("items", "page", "size", "totalElements", "totalPages"), "작업자 목록")
    if not isinstance(body["items"], list):
        raise ValueError("작업자 목록의 items가 배열이 아닙니다.")


def check_worker_info(data, expected, label):
    body = require_fields(data, ("workerId", "workerName", "sex", "age", "enabled"), label)
    actual = (body["workerId"], body["workerName"], body["sex"], body["age"], body["enabled"])
    if actual != expected:
        raise ValueError(f"{label} 응답이 예상한 작업자 정보와 일치하지 않습니다.")


def check_login(response, data):
    body = require_fields(data, ("accessToken", "tokenType", "expiresAt"), "작업자 로그인")
    if not isinstance(body["accessToken"], str) or not body["accessToken"]:
        raise ValueError("작업자 로그인 응답의 accessToken이 비어 있습니다.")
    if body["tokenType"] != "Bearer":
        raise ValueError("작업자 로그인 응답의 tokenType이 Bearer가 아닙니다.")
    if not isinstance(body["expiresAt"], str) or not body["expiresAt"]:
        raise ValueError("작업자 로그인 응답의 expiresAt이 비어 있습니다.")


def check_event_created(data, worker_id):
    body = require_fields(data, ("eventId", "workerId", "eventType", "alertSender"), "이벤트 등록")
    require_positive_id(body, "eventId", "이벤트 등록")
    if (body["workerId"], body["eventType"], body["alertSender"]) != (
            worker_id, "FALL_DETECTED", "API_TESTER"):
        raise ValueError("이벤트 등록 응답이 요청과 일치하지 않습니다.")


def check_event_list(data, event_id):
    body = require_fields(data, ("items",), "이벤트 목록")
    if not isinstance(body["items"], list) or not any(
            item.get("eventId") == event_id for item in body["items"] if isinstance(item, dict)):
        raise ValueError("이벤트 목록에서 방금 생성한 이벤트를 찾지 못했습니다.")


def check_event_detail(data, event_id, worker_id):
    body = require_fields(
        data, ("eventId", "workerId", "eventType", "alertSender", "processedAt", "adminMemo"),
        "이벤트 상세",
    )
    if (body["eventId"], body["workerId"], body["eventType"], body["alertSender"],
            body["processedAt"], body["adminMemo"]) != (
            event_id, worker_id, "FALL_DETECTED", "API_TESTER", None, None):
        raise ValueError("이벤트 상세 응답이 생성 요청과 일치하지 않습니다.")


def check_processed_event(data, event_id, memo):
    body = require_fields(data, ("eventId", "processedAt", "processedBy", "adminMemo"), "이벤트 처리")
    if body["eventId"] != event_id or not body["processedAt"]:
        raise ValueError("이벤트 처리 응답에 처리 정보가 없습니다.")
    if body["adminMemo"] != memo:
        raise ValueError("이벤트 처리 응답에 저장된 메모가 없습니다.")
    require_positive_id(body, "processedBy", "이벤트 처리")


def check_processed_detail(data, event_id, worker_id, memo):
    body = require_fields(
        data, ("eventId", "workerId", "processedAt", "processedBy", "adminMemo"),
        "처리 이벤트 상세",
    )
    if (body["eventId"], body["workerId"], body["adminMemo"]) != (
            event_id, worker_id, memo) or not body["processedAt"]:
        raise ValueError("이벤트 상세 조회에서 처리 메모가 일치하지 않습니다.")
    require_positive_id(body, "processedBy", "처리 이벤트 상세")


def check_reset_password(data, worker_id):
    body = require_fields(data, ("workerId", "temporaryPassword"), "비밀번호 초기화")
    if body["workerId"] != worker_id or not isinstance(body["temporaryPassword"], str) or not body["temporaryPassword"]:
        raise ValueError("비밀번호 초기화 응답이 올바르지 않습니다.")


def check_empty(response, data):
    if response.content:
        raise ValueError("204 응답 본문이 비어 있지 않습니다.")


def check_location(response, data, suffix, label):
    if not response.headers.get("location", "").endswith(suffix):
        raise ValueError(f"{label} 후 이동 경로가 예상과 다릅니다.")


def main():
    base_url = input("서버 주소 [http://localhost:8080]: ").strip()
    if not base_url:
        base_url = "http://localhost:8080"
    if not base_url.startswith(("http://", "https://")):
        print("서버 주소는 http:// 또는 https://로 시작해야 합니다.", file=sys.stderr)
        return 1

    admin_id = input("관리자 ID: ")
    admin_password = getpass.getpass("관리자 비밀번호: ")

    try:
        with httpx.Client(timeout=TIMEOUT, follow_redirects=False) as admin, \
             httpx.Client(timeout=TIMEOUT, follow_redirects=False) as worker:
            request_and_check(
                admin, "GET", "/health", 200, base_url=base_url,
                check=check_status,
            )

            login_csrf = CsrfParser()
            request_and_check(
                admin, "GET", "/admin/login", 200, base_url=base_url,
                check=lambda response, data: check_csrf_form(response, login_csrf),
            )

            request_and_check(
                admin, "POST", "/admin/login", 302, base_url=base_url,
                form={"adminId": admin_id, "password": admin_password,
                      "_csrf": login_csrf.form_token},
                check=lambda response, data: check_location(
                    response, data, "/admin/dashboard", "관리자 로그인"
                ),
            )

            csrf = CsrfParser()
            request_and_check(
                admin, "GET", "/admin/events", 200, base_url=base_url,
                check=lambda response, data: check_csrf_page(response, data, csrf),
            )
            admin_headers = {csrf.header_name: csrf.header_token}

            _, created = request_and_check(
                admin, "POST", "/api/admin/workers", 201, base_url=base_url,
                headers=admin_headers,
                json_body={"workerName": "API 테스트 작업자", "sex": "U", "age": 25},
                check=check_worker_created,
            )
            worker_id = created["workerId"]
            temporary_password = created["temporaryPassword"]

            request_and_check(
                admin, "GET", "/api/admin/workers", 200, base_url=base_url,
                params={"page": 0, "size": 20},
                check=check_worker_list,
            )

            request_and_check(
                admin, "GET", f"/api/admin/workers/{worker_id}", 200, base_url=base_url,
                check=lambda response, data: check_worker_info(
                    data, (worker_id, "API 테스트 작업자", "U", 25, True), "작업자 상세"
                ),
            )

            def worker_login(password):
                _, result = request_and_check(
                    worker, "POST", "/api/worker/auth/login", 200, base_url=base_url,
                    json_body={"workerId": worker_id, "password": password},
                    check=check_login,
                )
                return result["accessToken"]

            token = worker_login(temporary_password)
            worker_headers = {"Authorization": f"Bearer {token}"}

            request_and_check(
                worker, "PATCH", "/api/worker/me", 200, base_url=base_url,
                headers=worker_headers,
                json_body={"workerName": "API 테스트 작업자 수정"},
                check=lambda response, data: check_worker_info(
                    data, (worker_id, "API 테스트 작업자 수정", "U", 25, True), "프로필 수정"
                ),
            )

            event_time = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            _, event = request_and_check(
                worker, "POST", "/api/worker/events", 201, base_url=base_url,
                headers=worker_headers,
                json_body={"time": event_time, "eventType": "FALL_DETECTED",
                           "alertSender": "API_TESTER"},
                check=lambda response, data: check_event_created(data, worker_id),
            )
            event_id = event["eventId"]

            request_and_check(
                admin, "GET", "/api/admin/events", 200, base_url=base_url,
                params={"page": 0, "size": 20, "workerId": worker_id},
                check=lambda response, data: check_event_list(data, event_id),
            )

            request_and_check(
                admin, "GET", f"/api/admin/events/{event_id}", 200, base_url=base_url,
                check=lambda response, data: check_event_detail(data, event_id, worker_id),
            )

            admin_memo = "API 테스터 처리 확인"
            request_and_check(
                admin, "PATCH", f"/api/admin/events/{event_id}/process", 200,
                base_url=base_url, headers=admin_headers,
                json_body={"adminMemo": admin_memo},
                check=lambda response, data: check_processed_event(data, event_id, admin_memo),
            )

            request_and_check(
                admin, "GET", f"/api/admin/events/{event_id}", 200, base_url=base_url,
                check=lambda response, data: check_processed_detail(
                    data, event_id, worker_id, admin_memo
                ),
            )

            request_and_check(
                worker, "POST", "/api/worker/auth/logout", 204, base_url=base_url,
                headers=worker_headers,
                check=check_empty,
            )

            token = worker_login(temporary_password)
            worker_headers = {"Authorization": f"Bearer {token}"}
            new_password = "ApiTest-" + "".join(secrets.choice(string.ascii_letters + string.digits)
                                                  for _ in range(16))
            request_and_check(
                worker, "PATCH", "/api/worker/me/password", 204, base_url=base_url,
                headers=worker_headers,
                json_body={"currentPassword": temporary_password, "newPassword": new_password},
                check=check_empty,
            )

            token = worker_login(new_password)
            worker_headers = {"Authorization": f"Bearer {token}"}
            _, reset = request_and_check(
                admin, "POST", f"/api/admin/workers/{worker_id}/reset-password", 200,
                base_url=base_url, headers=admin_headers,
                check=lambda response, data: check_reset_password(data, worker_id),
            )
            reset_password = reset["temporaryPassword"]

            token = worker_login(reset_password)
            worker_headers = {"Authorization": f"Bearer {token}"}
            request_and_check(
                worker, "DELETE", "/api/worker/me", 204, base_url=base_url,
                headers=worker_headers,
                json_body={"currentPassword": reset_password},
                check=check_empty,
            )

            request_and_check(
                admin, "POST", "/admin/logout", 302, base_url=base_url,
                headers=admin_headers,
                check=lambda response, data: check_location(
                    response, data, "/admin/login?logout", "관리자 로그아웃"
                ),
            )

    except CheckFailed:
        return 1
    except (ValueError, TypeError, KeyError) as error:
        print(f"KO\n이유: {error}")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
