package org.example.workersafety_server.dto;

import jakarta.validation.constraints.*;
import org.example.workersafety_server.entity.EventType;
import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() { }
    public record Login(@NotNull @Positive Long workerId, @NotBlank String password) {
        @Override public String toString() { return "Login[workerId=" + workerId + ", password=[PROTECTED]]"; }
    }
    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {
        @Override public String toString() { return "TokenResponse[accessToken=[PROTECTED], tokenType=" + tokenType + ", expiresAt=" + expiresAt + "]"; }
    }
    public record NewWorker(@NotBlank @Size(max = 50) String workerName,
                            @NotNull @Pattern(regexp = "M|F|U") String sex,
                            @NotNull @Min(0) @Max(150) Integer age) { }
    public record WorkerInfo(Long workerId, String workerName, String sex, Integer age, boolean enabled) { }
    public record WorkerCreated(Long workerId, String temporaryPassword) {
        @Override public String toString() { return "WorkerCreated[workerId=" + workerId + ", temporaryPassword=[PROTECTED]]"; }
    }
    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {
        @Override public String toString() { return "PasswordChange[passwords=[PROTECTED]]"; }
    }
    public record PasswordConfirmation(@NotBlank String currentPassword) {
        @Override public String toString() { return "PasswordConfirmation[password=[PROTECTED]]"; }
    }
    public record NewEvent(@NotNull Instant time, @NotNull EventType eventType,
                           @NotBlank @Size(max = 100) String alertSender) { }
    public record ProcessEventRequest(@NotBlank String adminMemo) { }
    public record EventInfo(Long eventId, Long workerId, String workerName, Instant time, Instant receivedAt,
                            EventType eventType, String alertSender, Instant processedAt, Long processedBy,
                            String adminMemo) { }
    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) { }
    public record ErrorResponse(String code, String message) { }
    public record Dashboard(long workerCount, long eventCount, long pendingCount, List<EventInfo> recentEvents) { }
}
