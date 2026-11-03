package org.example.workersafety_server.dto;

import java.time.Instant;
import java.util.List;

/** DTOs for the administrator design's domain APIs. TODO fields are fixed until their data sources exist. */
public final class AdminUiModels {
    private AdminUiModels() { }

    public record WorkerStatus(long workerId, String workerName, String status,
                               String monitoringStatus, String connectionStatus,
                               String connectionHealth, String zoneName, Instant lastSeenAt,
                               String heartRateStatus, String spo2Status, Instant measuredAt,
                               List<Device> devices, SafetyCheck safetyCheck, Proximity proximity) { }
    public record Device(String type, Instant lastSeenAt) { }
    public record SafetyCheck(String status, String tagId, int intervalMinutes,
                              Instant requestedAt, Instant dueAt, Instant lastConfirmedAt,
                              Instant nextRequestAt) { }
    public record Proximity(String beaconId, String restrictedAreaId, double distanceMeters,
                            double thresholdMeters, Instant measuredAt, boolean insideRestricted,
                            Instant insideSince, String status, int dwellMinutes) { }
    public record Zone(String id, String name, int workerCount, int atRiskCount) { }
    public record ZoneDetail(String id, String name, List<WorkerStatus> workers) { }
    public record Warning(long id, long workerId, String workerName, Instant at, String message) { }
    public record RestrictedResponse(long id, long workerId, String workerName,
                                     String message, Instant at, String actor) { }
    public record Weather(String siteName, double temperatureC, int humidityPercent,
                          String condition, Instant measuredAt, String source) { }
    public record WeatherNotice(long id, String message, String actor, Instant createdAt) { }
    public record AuditRecord(long eventId, long workerId, String workerName,
                              String action, Instant at, String actor, String note) { }
    public record Settings(int staleMinutes, int offlineMinutes, String alertSeverity) { }
    public record Admin(long adminId, String name, String email, String role, boolean active) { }
    public record Dashboard(long total, long normal, long danger, long offline, long pending,
                            long urgentCount, long newCount, long acknowledgedCount,
                            List<UiEvent> recentEvents, List<Zone> zones,
                            List<WorkerStatus> workers) { }
    public record UiEvent(long eventId, long workerId, String workerName, String type,
                          String severity, String status, String message, String zoneName,
                          Instant occurredAt, Instant receivedAt, Instant acknowledgedAt,
                          Instant resolvedAt, String note) { }
    public record ActionResult(long eventId, long workerId, String status, Instant at,
                               String actor, String message) { }
    public record NoticeRequest(@jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 500) String message) { }
    public record ResolveRequest(@jakarta.validation.constraints.NotBlank
                                 @jakarta.validation.constraints.Size(max = 500) String adminMemo) { }
    public record SettingsRequest(@jakarta.validation.constraints.Min(1)
                                  @jakarta.validation.constraints.Max(60) int staleMinutes,
                                  @jakarta.validation.constraints.Min(2)
                                  @jakarta.validation.constraints.Max(240) int offlineMinutes,
                                  @jakarta.validation.constraints.NotBlank
                                  @jakarta.validation.constraints.Pattern(regexp = "MEDIUM|HIGH|CRITICAL")
                                  String alertSeverity) { }
    public record RoleRequest(@jakarta.validation.constraints.NotBlank
                              @jakarta.validation.constraints.Pattern(regexp = "ADMIN|VIEWER") String role) { }
    public record EnabledRequest(@jakarta.validation.constraints.NotNull Boolean enabled) { }
    public record AdminRequest(@jakarta.validation.constraints.NotBlank
                               @jakarta.validation.constraints.Size(max = 50) String name,
                               @jakarta.validation.constraints.NotBlank
                               @jakarta.validation.constraints.Email
                               @jakarta.validation.constraints.Size(max = 120) String email,
                               @jakarta.validation.constraints.NotBlank
                               @jakarta.validation.constraints.Pattern(regexp = "ADMIN|VIEWER") String role) { }
    public record WorkerCreateRequest(@jakarta.validation.constraints.NotBlank
                                      @jakarta.validation.constraints.Size(max = 50) String name,
                                      @jakarta.validation.constraints.NotBlank
                                      @jakarta.validation.constraints.Size(max = 100) String zoneId) { }
    public record WorkerCreateResult(long workerId, String message) { }
    public record SettingsResult(Settings settings, String message) { }
    public record AdminResult(Admin admin, String message) { }
    public record NoticeResult(WeatherNotice notice, String message) { }
    public record WorkerActionResult(long workerId, String status, String message, Instant at) { }
}
