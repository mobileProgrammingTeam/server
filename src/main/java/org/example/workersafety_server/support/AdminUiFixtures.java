package org.example.workersafety_server.support;

import org.example.workersafety_server.dto.AdminUiModels.*;
import java.time.Instant;
import java.util.List;

/** Fixed response values for domain APIs whose data sources are planned but not implemented yet. */
public final class AdminUiFixtures {
    private AdminUiFixtures() { }
    private static Instant at(String value) { return Instant.parse(value); }

    public static Dashboard dashboard() {
        return new Dashboard(4, 1, 2, 1, 5, 4, 4, 1, EVENTS.subList(0, 5), ZONES, WORKERS);
    }

    public static ZoneDetail zone(String zoneId) {
        return new ZoneDetail(zoneId, ZONES.get(0).name(), WORKERS.subList(0, 2));
    }

    public static final List<WorkerStatus> WORKERS = List.of(
            new WorkerStatus(101, "김민준", "DANGER", "RUNNING", "ONLINE", "ONLINE", "A 작업구역",
                    at("2026-11-01T23:58:00Z"), "DANGER", "NORMAL", at("2026-11-01T23:57:00Z"),
                    List.of(new Device("휴대폰", at("2026-11-01T23:58:00Z")), new Device("워치", at("2026-11-01T23:57:00Z"))),
                    new SafetyCheck("PENDING", "NFC-A-12", 30, at("2026-11-01T23:57:00Z"), at("2026-11-02T00:00:00Z"), null, null),
                    null),
            new WorkerStatus(102, "이서연", "NORMAL", "RUNNING", "ONLINE", "ONLINE", "A 작업구역",
                    at("2026-11-01T23:58:00Z"), "NORMAL", "NORMAL", at("2026-11-01T23:56:00Z"),
                    List.of(new Device("휴대폰", at("2026-11-01T23:58:00Z")), new Device("워치", at("2026-11-01T23:56:00Z"))),
                    new SafetyCheck("CONFIRMED", "NFC-A-08", 30, at("2026-11-01T23:56:00Z"), null, at("2026-11-01T23:56:00Z"), at("2026-11-02T00:26:00Z")),
                    null),
            new WorkerStatus(103, "박지훈", "WARNING", "RUNNING", "ONLINE", "STALE", "B 작업구역",
                    at("2026-11-01T23:51:00Z"), "NORMAL", "UNKNOWN", at("2026-11-01T23:50:00Z"),
                    List.of(new Device("휴대폰", at("2026-11-01T23:51:00Z")), new Device("워치", at("2026-11-01T23:44:00Z"))),
                    new SafetyCheck("CONFIRMED", "NFC-B-07", 30, at("2026-11-01T23:50:00Z"), null, at("2026-11-01T23:50:00Z"), at("2026-11-02T00:20:00Z")),
                    new Proximity("BEACON-B-03", "restricted-1", 1.6, 2.0, at("2026-11-01T23:58:00Z"), true,
                            at("2026-11-01T23:46:00Z"), "DANGER", 12)),
            new WorkerStatus(104, "최도윤", "OFFLINE", "STOPPED", "OFFLINE", "OFFLINE", "미배정",
                    at("2026-11-01T22:58:00Z"), "UNKNOWN", "UNKNOWN", at("2026-11-01T22:50:00Z"),
                    List.of(new Device("휴대폰", at("2026-11-01T22:58:00Z")), new Device("워치", null)),
                    new SafetyCheck("UNKNOWN", "NFC-C-02", 30, null, null, null, null), null)
    );

    public static final List<Zone> ZONES = List.of(
            new Zone("zone-a", "A 작업구역", 2, 1),
            new Zone("zone-b", "B 작업구역", 1, 1),
            new Zone("unassigned", "미배정", 1, 0));

    public static final List<UiEvent> EVENTS = List.of(
            new UiEvent(1001, 101, "김민준", "FALL", "CRITICAL", "NEW", "낙상 감지 샘플 이벤트", "A 작업구역",
                    at("2026-11-01T23:57:00Z"), at("2026-11-01T23:57:01Z"), null, null, ""),
            new UiEvent(1002, 103, "박지훈", "DANGER_ZONE_ENTRY", "HIGH", "ACKNOWLEDGED", "제한구역 진입 예시", "B 작업구역",
                    at("2026-11-01T23:50:00Z"), at("2026-11-01T23:50:02Z"), at("2026-11-01T23:52:00Z"), null, "현장 확인 중"),
            new UiEvent(1003, 102, "이서연", "VITAL_ABNORMAL", "MEDIUM", "RESOLVED", "생체신호 이상 예시", "A 작업구역",
                    at("2026-11-01T23:42:00Z"), at("2026-11-01T23:42:01Z"), at("2026-11-01T23:44:00Z"), at("2026-11-01T23:48:00Z"), "현장 확인 완료"),
            new UiEvent(1004, 102, "이서연", "MANUAL_REPORT", "HIGH", "REQUEST", "직접 위험 신고 예시", "A 작업구역",
                    at("2026-11-01T23:38:00Z"), at("2026-11-01T23:38:01Z"), null, null, ""),
            new UiEvent(1005, 104, "최도윤", "AUTO_SOS", "CRITICAL", "NEW", "NFC 안전확인 미응답 예시", "미배정",
                    at("2026-11-01T23:35:00Z"), at("2026-11-01T23:35:01Z"), null, null, ""),
            new UiEvent(1006, 101, "김민준", "ADMIN_SOS", "CRITICAL", "NEW", "관리자 SOS 요청 예시", "A 작업구역",
                    at("2026-11-01T23:30:00Z"), at("2026-11-01T23:30:01Z"), null, null, "")
    );

    public static final List<Warning> WARNINGS = List.of(
            new Warning(2001, 103, "박지훈", at("2026-11-01T23:46:00Z"), "제한구역에 접근했습니다. 안전 구역으로 이동하세요."));
    public static final List<RestrictedResponse> RESTRICTED_RESPONSES = List.of(
            new RestrictedResponse(3001, 103, "박지훈", "관리자 대응 메시지 예시입니다.", at("2026-11-01T23:55:00Z"), "관리자"));
    public static final Weather WEATHER = new Weather("현장 지역 미설정", 33, 68, "더위", at("2026-11-01T23:55:00Z"), "DEMO");
    public static final List<WeatherNotice> WEATHER_NOTICES = List.of(
            new WeatherNotice(4001, "고온 환경에서 휴식 시간을 확인하세요.", "관리자", at("2026-11-01T23:40:00Z")));
    public static final List<AuditRecord> AUDIT = List.of(
            new AuditRecord(1003, 102, "이서연", "resolve", at("2026-11-01T23:48:00Z"), "관리자", "현장 확인 완료"),
            new AuditRecord(1002, 103, "박지훈", "acknowledge", at("2026-11-01T23:52:00Z"), "관리자", "현장 확인 중"));
    public static final Settings SETTINGS = new Settings(5, 15, "HIGH");
    public static final List<Admin> ADMINS = List.of(
            new Admin(1, "안전 총괄", "admin@example.invalid", "SUPER_ADMIN", true),
            new Admin(2, "현장 관리자", "manager@example.invalid", "ADMIN", true),
            new Admin(3, "안전 조회자", "viewer@example.invalid", "VIEWER", false));
}
