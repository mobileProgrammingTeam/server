package org.example.workersafety_server.controller.api;

import jakarta.validation.Valid;
import org.example.workersafety_server.dto.AdminUiModels.*;
import org.example.workersafety_server.dto.ApiModels.PageResponse;
import org.example.workersafety_server.support.AdminUiFixtures;
import org.springframework.web.bind.annotation.*;

/** Domain endpoints whose upstream data and commands are not implemented yet. */
@RestController
@RequestMapping("/api/admin")
public class AdminDomainApiController {

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        // TODO: replace these fixture totals with server-side summaries and live event/worker queries.
        return AdminUiFixtures.dashboard();
    }

    @GetMapping("/workers/status")
    public PageResponse<WorkerStatus> workerStatuses(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        // TODO: retrieve the requested page of current safety, connection and vital status from worker telemetry.
        return new PageResponse<>(AdminUiFixtures.WORKERS, 0, size, AdminUiFixtures.WORKERS.size(), 1);
    }

    @GetMapping("/workers/{workerId}/status")
    public WorkerStatus workerStatus(@PathVariable long workerId) {
        // TODO: load this worker's latest app/device telemetry, safety check and beacon observations.
        var sample = AdminUiFixtures.WORKERS.get(0);
        return new WorkerStatus(workerId, sample.workerName(), sample.status(), sample.monitoringStatus(),
                sample.connectionStatus(), sample.connectionHealth(), sample.zoneName(), sample.lastSeenAt(),
                sample.heartRateStatus(), sample.spo2Status(), sample.measuredAt(), sample.devices(),
                sample.safetyCheck(), sample.proximity());
    }

    @GetMapping("/zones")
    public java.util.List<Zone> zones() {
        // TODO: query registered work zones and aggregate their live worker status.
        return AdminUiFixtures.ZONES;
    }

    @GetMapping("/zones/{zoneId}")
    public ZoneDetail zone(@PathVariable String zoneId) {
        // TODO: validate the zone and load its assigned workers and current status.
        return AdminUiFixtures.zone(zoneId);
    }

    @GetMapping("/restricted-areas/warnings")
    public java.util.List<Warning> warnings() {
        // TODO: read actual proximity alerts and delivery results.
        return AdminUiFixtures.WARNINGS;
    }

    @GetMapping("/restricted-areas/responses")
    public java.util.List<RestrictedResponse> restrictedResponses() {
        // TODO: read persisted manager responses and device-delivery status.
        return AdminUiFixtures.RESTRICTED_RESPONSES;
    }

    @GetMapping("/weather/current")
    public Weather weather() {
        // TODO: retrieve weather for the configured site and include the provider and observation time.
        return AdminUiFixtures.WEATHER;
    }

    @GetMapping("/weather/notices")
    public java.util.List<WeatherNotice> weatherNotices() {
        // TODO: query persisted safety notices.
        return AdminUiFixtures.WEATHER_NOTICES;
    }

    @PostMapping("/weather/notices")
    public NoticeResult createWeatherNotice(@Valid @RequestBody NoticeRequest request) {
        // TODO: authorize the sender, persist the notice and send it to active administrators.
        return new NoticeResult(new WeatherNotice(4002, request.message(), "관리자", AdminUiFixtures.WEATHER_NOTICES.get(0).createdAt()),
                "주의 메시지 등록 예시입니다.");
    }

    @GetMapping("/audit/events")
    public java.util.List<AuditRecord> auditEvents() {
        // TODO: query immutable event audit records with server-side filters and pagination.
        return AdminUiFixtures.AUDIT;
    }

    @GetMapping("/settings")
    public Settings settings() {
        // TODO: load the persisted notification and connection thresholds.
        return AdminUiFixtures.SETTINGS;
    }

    @PatchMapping("/settings")
    public SettingsResult updateSettings(@Valid @RequestBody SettingsRequest request) {
        // TODO: validate cross-field rules, authorize the change, persist it and append an audit record.
        return new SettingsResult(new Settings(request.staleMinutes(), request.offlineMinutes(), request.alertSeverity()),
                "설정 변경 예시입니다.");
    }

    @GetMapping("/admins")
    public java.util.List<Admin> admins() {
        // TODO: query real administrator accounts and their assigned permissions.
        return AdminUiFixtures.ADMINS;
    }

    @PostMapping("/admins")
    public AdminResult createAdmin(@Valid @RequestBody AdminRequest request) {
        // TODO: enforce administrator policy, create credentials, notify the user and record the change.
        return new AdminResult(new Admin(4, request.name(), request.email(), request.role(), true),
                "관리자 등록 예시입니다.");
    }

    @PatchMapping("/admins/{adminId}/role")
    public AdminResult changeRole(@PathVariable long adminId, @Valid @RequestBody RoleRequest request) {
        // TODO: verify the current administrator, update permissions and append an audit record.
        return new AdminResult(new Admin(adminId, "안전 조회자", "viewer@example.invalid", request.role(), true),
                "관리자 역할 변경 예시입니다.");
    }

    @PatchMapping("/admins/{adminId}/enabled")
    public AdminResult setAdminEnabled(@PathVariable long adminId, @Valid @RequestBody EnabledRequest request) {
        // TODO: enforce last-administrator and self-disable policy, update the account and audit the change.
        return new AdminResult(new Admin(adminId, "현장 관리자", "manager@example.invalid", "ADMIN", request.enabled()),
                "관리자 상태 변경 예시입니다.");
    }

    @PatchMapping("/events/{eventId}/acknowledge")
    public ActionResult acknowledge(@PathVariable long eventId) {
        // TODO: load the event, validate its transition, persist the actor/time and append an audit record.
        return new ActionResult(eventId, 101, "ACKNOWLEDGED", AdminUiFixtures.EVENTS.get(1).acknowledgedAt(),
                "관리자", "이벤트 확인 응답 예시입니다.");
    }

    @PatchMapping("/events/{eventId}/resolve")
    public ActionResult resolve(@PathVariable long eventId, @Valid @RequestBody ResolveRequest request) {
        // TODO: require the acknowledged state, persist the resolution and memo, and audit the change.
        return new ActionResult(eventId, 101, "RESOLVED", AdminUiFixtures.EVENTS.get(2).resolvedAt(),
                "관리자", request.adminMemo());
    }

    @PostMapping("/workers/{workerId}/safety-check")
    public WorkerActionResult requestSafetyCheck(@PathVariable long workerId) {
        // TODO: create a safety-check request and deliver it to the worker device.
        return new WorkerActionResult(workerId, "PENDING", "안전확인 요청 응답 예시입니다.",
                AdminUiFixtures.WORKERS.get(0).safetyCheck().requestedAt());
    }

    @PostMapping("/workers/{workerId}/sos")
    public WorkerActionResult requestSos(@PathVariable long workerId) {
        // TODO: authorize, deduplicate, dispatch and audit the SOS request.
        return new WorkerActionResult(workerId, "REQUESTED", "SOS 요청 응답 예시입니다.",
                AdminUiFixtures.EVENTS.get(0).occurredAt());
    }

    @PostMapping("/workers/{workerId}/restricted-response")
    public WorkerActionResult restrictedResponse(@PathVariable long workerId,
                                                 @Valid @RequestBody NoticeRequest request) {
        // TODO: verify the restriction/dwell state, dispatch the message and persist delivery/audit results.
        return new WorkerActionResult(workerId, "SENT", request.message(),
                AdminUiFixtures.RESTRICTED_RESPONSES.get(0).at());
    }
}
