package org.example.workersafety_server.service;

import jakarta.annotation.PostConstruct;
import org.example.workersafety_server.dto.ApiModels.*;
import org.example.workersafety_server.entity.*;
import org.example.workersafety_server.exception.ApiException;
import org.example.workersafety_server.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.workersafety_server.service.event.AdminDataChangedEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Service
public class SafetyService {
    private final AccountRepository accounts;
    private final WorkerProfileRepository workers;
    private final SafetyEventRepository events;
    private final MobileTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Duration tokenTtl;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

    public SafetyService(AccountRepository accounts, WorkerProfileRepository workers, SafetyEventRepository events,
                         MobileTokenRepository tokens, PasswordEncoder encoder, Clock clock, TransactionTemplate transactions,
                         @Value("${app.token-ttl:PT12H}") Duration tokenTtl, ApplicationEventPublisher eventPublisher) {
        this.accounts = accounts; this.workers = workers; this.events = events; this.tokens = tokens;
        this.encoder = encoder; this.clock = clock; this.transactions = transactions; this.tokenTtl = tokenTtl; this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void validateConfiguration() {
        if (tokenTtl.compareTo(Duration.ofMillis(1)) < 0) throw new IllegalStateException("MOBILE_TOKEN_TTL must be at least 1ms");
    }

    public void bootstrap(String loginId, String password) {
        transactions.executeWithoutResult(status -> {
                if (accounts.existsByRole(Role.ADMIN)) {
                    if (!accounts.existsByRoleAndEnabledTrue(Role.ADMIN)) throw new IllegalStateException("No active administrator account exists");
                    return;
                }
                if (loginId == null || loginId.isBlank() || password == null || password.isBlank())
                    throw new IllegalStateException("ADMIN_LOGIN_ID and ADMIN_INITIAL_PASSWORD are required to create the first administrator");
                validatePassword(password);
                Account account = new Account();
                account.setAdminLoginId(loginId.trim()); account.setPasswordHash(encoder.encode(password));
                account.setRole(Role.ADMIN); account.setEnabled(true); account.setCreatedAt(now()); account.setUpdatedAt(now());
                accounts.save(account);
        });
    }

    @Transactional
    public WorkerCreated createWorker(NewWorker request) {
        String password = "0000";
        Account account = new Account();
        account.setRole(Role.WORKER); account.setEnabled(true); account.setPasswordHash(encoder.encode(password));
        account.setCreatedAt(now()); account.setUpdatedAt(now());
        accounts.saveAndFlush(account);
        WorkerProfile profile = new WorkerProfile();
        profile.setAccount(account); profile.setWorkerName(request.workerName().trim());
        profile.setSex(request.sex()); profile.setAge(request.age());
        workers.saveAndFlush(profile);
        eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-created", account.getId()));
        return new WorkerCreated(account.getId(), password);
    }

    public TokenResponse login(Login request) {
        TokenResponse result = transactions.execute(status -> {
                Account account = accounts.findByIdForUpdate(request.workerId()).orElseThrow(ApiException::unauthorized);
                if (account.getRole() != Role.WORKER || !account.isEnabled() || account.getDeletedAt() != null) throw ApiException.unauthorized();
                if (account.getLoginLockedAt() != null) return null;
                if (!matchesPassword(request.password(), account.getPasswordHash())) {
                    account.setFailedLoginCount(account.getFailedLoginCount() + 1);
                    if (account.getFailedLoginCount() >= 6) {
                        account.setLoginLockedAt(now());
                        tokens.revokeAll(account.getId(), now());
                        eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", account.getId()));
                    }
                    return null;
                }
                account.setFailedLoginCount(0);
                String raw = randomString(32);
                Instant issued = now();
                MobileToken token = new MobileToken();
                token.setTokenHash(hash(raw)); token.setAccount(account); token.setIssuedAt(issued); token.setExpiresAt(issued.plus(tokenTtl));
                tokens.save(token);
                return new TokenResponse(raw, "Bearer", token.getExpiresAt());
            });
        if (result == null) throw ApiException.unauthorized();
        return result;
    }

    @Transactional(readOnly = true)
    public Long authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() > 256 || rawToken.isBlank()) return null;
        return tokens.findById(hash(rawToken)).filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(now()))
                .filter(t -> t.getAccount().isEnabled() && t.getAccount().getDeletedAt() == null
                        && t.getAccount().getLoginLockedAt() == null && t.getAccount().getRole() == Role.WORKER)
                .map(t -> t.getAccount().getId()).orElse(null);
    }

    @Transactional
    public void logout(String rawToken) { tokens.revoke(hash(rawToken), now()); }

    @Transactional(readOnly = true)
    public WorkerInfo worker(long id) { return workerInfo(activeProfile(id)); }

    @Transactional(readOnly = true)
    public PageResponse<WorkerInfo> workers(int page, int size) {
        return response(workers.findAll(pageable(page, size, Sort.by("id"))).map(this::workerInfo));
    }

    @Transactional
    public WorkerInfo updateProfile(long id, Map<String, Object> changes) {
        if (changes.isEmpty() || !Set.of("workerName", "sex", "age").containsAll(changes.keySet()) || changes.containsValue(null))
            throw ApiException.badRequest("수정할 개인정보를 확인하세요.");
        Account account = accounts.findByIdForUpdate(id)
                .filter(a -> a.getRole() == Role.WORKER && a.isEnabled() && a.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
        WorkerProfile profile = workers.findById(id)
                .orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
        boolean changed = false;
        if (changes.containsKey("workerName")) {
            Object value = changes.get("workerName");
            if (!(value instanceof String name) || name.isBlank() || name.length() > 50) throw ApiException.badRequest("이름을 확인하세요.");
            String normalized = name.trim();
            changed |= !normalized.equals(profile.getWorkerName());
            profile.setWorkerName(normalized);
        }
        if (changes.containsKey("sex")) {
            Object value = changes.get("sex");
            if (!(value instanceof String sex) || !Set.of("M", "F", "U").contains(sex)) throw ApiException.badRequest("성별을 확인하세요.");
            changed |= !sex.equals(profile.getSex());
            profile.setSex(sex);
        }
        if (changes.containsKey("age")) {
            Object value = changes.get("age");
            if (!(value instanceof Integer age) || age < 0 || age > 150) throw ApiException.badRequest("나이를 확인하세요.");
            changed |= !age.equals(profile.getAge());
            profile.setAge(age);
        }
        account.setUpdatedAt(now());
        if (changed) eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-updated", id));
        return workerInfo(profile);
    }

    public void changePassword(long id, PasswordChange request) {
        validatePassword(request.newPassword());
            transactions.executeWithoutResult(status -> {
                Account account = accounts.findByIdForUpdate(id).filter(a -> a.getRole() == Role.WORKER && a.isEnabled() && a.getDeletedAt() == null).orElseThrow(ApiException::unauthorized);
                if (!matchesPassword(request.currentPassword(), account.getPasswordHash())) throw ApiException.unauthorized();
                boolean wasLocked = account.getLoginLockedAt() != null;
                account.setPasswordHash(encoder.encode(request.newPassword())); account.setUpdatedAt(now());
                account.setFailedLoginCount(0); account.setLoginLockedAt(null);
                tokens.revokeAll(id, now());
                if (wasLocked) eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", id));
            });
    }

    public WorkerCreated resetPassword(long id) {
            return transactions.execute(status -> {
                Account account = accounts.findByIdForUpdate(id).filter(a -> a.getRole() == Role.WORKER && a.isEnabled() && a.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
                String password = "0000";
                boolean wasLocked = account.getLoginLockedAt() != null;
                account.setPasswordHash(encoder.encode(password)); account.setUpdatedAt(now());
                account.setFailedLoginCount(0); account.setLoginLockedAt(null);
                tokens.revokeAll(id, now());
                if (wasLocked) eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", id));
                return new WorkerCreated(id, password);
            });
    }

    public void deleteAccount(long id, PasswordConfirmation request) {
            transactions.executeWithoutResult(status -> {
                Account account = accounts.findByIdForUpdate(id)
                        .filter(a -> a.getRole() == Role.WORKER && a.isEnabled() && a.getDeletedAt() == null)
                        .orElseThrow(ApiException::unauthorized);
                if (!matchesPassword(request.currentPassword(), account.getPasswordHash())) throw ApiException.unauthorized();
                WorkerProfile profile = workers.findById(id).orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
                deactivateWorker(profile);
            });
    }

    // Administrator deletion is repeatable. Keep the profile and event history in the database.
    public void deleteWorker(long id) {
        transactions.executeWithoutResult(status -> {
            Account account = accounts.findByIdForUpdate(id)
                    .filter(a -> a.getRole() == Role.WORKER)
                    .orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
            WorkerProfile profile = workers.findById(id)
                    .orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
            if (account.getDeletedAt() == null) deactivateWorker(profile);
        });
    }

    // Shared by worker withdrawal and administrator deletion to keep cleanup consistent.
    private void deactivateWorker(WorkerProfile profile) {
        Instant time = now();
        Account account = profile.getAccount();
        account.setEnabled(false);
        account.setDeletedAt(time);
        account.setUpdatedAt(time);
        account.setPasswordHash(encoder.encode(randomString(32)));
        profile.setWorkerName("탈퇴한 작업자");
        profile.setSex(null);
        profile.setAge(null);
        tokens.revokeAll(profile.getId(), time);
        eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-deleted", profile.getId()));
    }

    /** Execution/device status only: this method never creates a safety event. */
    @Transactional
    public WorkerStatusResult reportWorkerStatus(long id, WorkerStatusReport request) {
        accounts.findByIdForUpdate(id).orElseThrow(ApiException::unauthorized);
        WorkerProfile profile = activeProfile(id);
        Instant receivedAt = now();
        Instant reportedAt = observationTime(request.reportedAt(), receivedAt);
        Instant watchAt = null;
        if (request.devices() != null && !request.devices().isEmpty()) {
            watchAt = observationTime(request.devices().get(0).lastSeenAt(), receivedAt);
            if (watchAt.isAfter(reportedAt.plusSeconds(60)))
                throw ApiException.badRequest("워치 관측 시각을 확인하세요.");
        }
        // Heart assessments are independent of transport/monitoring timestamps.
        if ((request.heartRateStatus() == null) != (request.measuredAt() == null))
            throw ApiException.badRequest("heartRateStatus와 measuredAt을 함께 보내세요.");
        Instant measuredAt = request.measuredAt() == null ? null : observationTime(request.measuredAt(), receivedAt);
        if (measuredAt != null) {
            if (measuredAt.isAfter(reportedAt.plusSeconds(60)))
                throw ApiException.badRequest("심박 측정 시각을 확인하세요.");
            Instant oldMeasurement = profile.getHeartRateMeasuredAt();
            if (oldMeasurement != null && measuredAt.isBefore(oldMeasurement))
                throw ApiException.conflict("HEART_RATE_OUT_OF_ORDER", "이전 심박 판정은 반영할 수 없습니다.");
            if (oldMeasurement != null && measuredAt.equals(oldMeasurement)
                    && request.heartRateStatus() != profile.getHeartRateStatus())
                throw ApiException.conflict("HEART_RATE_CONFLICT", "같은 측정 시각의 심박 상태가 다릅니다.");
        }
        Instant previous = profile.getStatusReportedAt();
        if (previous != null && reportedAt.isBefore(previous))
            throw ApiException.conflict("WORKER_STATUS_OUT_OF_ORDER", "이전 상태 보고는 반영할 수 없습니다.");
        if (previous != null && reportedAt.equals(previous)
                && request.monitoringStatus() != profile.getMonitoringStatus())
            throw ApiException.conflict("WORKER_STATUS_CONFLICT", "같은 시각에 다른 감시 상태가 보고되었습니다.");
        if (measuredAt != null) {
            profile.setHeartRateStatus(request.heartRateStatus());
            profile.setHeartRateMeasuredAt(measuredAt);
        }
        profile.setMonitoringStatus(request.monitoringStatus());
        profile.setStatusReportedAt(reportedAt);
        profile.setLastSeenAt(receivedAt);
        if (watchAt != null && (profile.getWatchLastSeenAt() == null || watchAt.isAfter(profile.getWatchLastSeenAt())))
            profile.setWatchLastSeenAt(watchAt);
        workers.saveAndFlush(profile);
        eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", id));
        return new WorkerStatusResult(profile.getMonitoringStatus(), profile.getStatusReportedAt(),
                profile.getLastSeenAt(), profile.getWatchLastSeenAt(), profile.getHeartRateStatus(),
                profile.getHeartRateMeasuredAt(), HeartRateReportPolicy.freshness(profile.getHeartRateMeasuredAt(), receivedAt));
    }

    private Instant observationTime(Instant value, Instant receivedAt) {
        try {
            Instant normalized = Instant.ofEpochMilli(value.toEpochMilli());
            if (!normalized.isAfter(Instant.EPOCH) || normalized.isAfter(receivedAt.plusSeconds(60)))
                throw ApiException.badRequest("보고 시각을 확인하세요.");
            return normalized;
        } catch (ArithmeticException ex) {
            throw ApiException.badRequest("보고 시각을 확인하세요.");
        }
    }

    @Transactional
    public EventInfo createEvent(long id, NewEvent request) {
        // Preserve the app's existing event contract. Heart-rate risk arrives ONLY here.
        boolean heartRate = request.eventType() == EventType.ABNORMAL_VITAL
                && "ANDROID_HEART_RATE".equals(request.alertSender().trim());
        accounts.findByIdForUpdate(id).orElseThrow(ApiException::unauthorized);
        WorkerProfile profile = activeProfile(id);
        Instant receivedAt = now();
        Instant occurredAt = heartRate ? observationTime(request.time(), receivedAt)
                : Instant.ofEpochMilli(request.time().toEpochMilli());
        profile.setLastSeenAt(receivedAt);
        if (heartRate) {
            var duplicate = events.findFirstByWorker_IdAndEventTypeAndAlertSenderAndOccurredAtOrderByIdAsc(
                    id, EventType.ABNORMAL_VITAL, "ANDROID_HEART_RATE", occurredAt);
            if (duplicate.isPresent()) {
                eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", id));
                return eventInfo(duplicate.get());
            }
        }
        SafetyEvent event = new SafetyEvent();
        event.setWorker(profile); event.setOccurredAt(occurredAt); event.setReceivedAt(receivedAt);
        event.setEventType(request.eventType()); event.setAlertSender(request.alertSender().trim());
        SafetyEvent saved = events.saveAndFlush(event);
        eventPublisher.publishEvent(AdminDataChangedEvent.event("event-created", saved.getId(), id));
        eventPublisher.publishEvent(AdminDataChangedEvent.worker("worker-status-changed", id));
        return eventInfo(saved);
    }

    @Transactional(readOnly = true)
    public EventInfo event(long id) { return eventInfo(findEvent(id)); }

    @Transactional(readOnly = true)
    public PageResponse<EventInfo> events(Boolean processed, Long workerId, EventType type, int page, int size) {
        if (workerId != null && workerId <= 0) throw ApiException.badRequest("작업자 ID를 확인하세요.");
        Specification<SafetyEvent> spec = (root, query, cb) -> {
            var result = cb.conjunction();
            if (processed != null) result = cb.and(result, processed ? cb.isNotNull(root.get("processedAt")) : cb.isNull(root.get("processedAt")));
            if (workerId != null) result = cb.and(result, cb.equal(root.get("worker").get("id"), workerId));
            if (type != null) result = cb.and(result, cb.equal(root.get("eventType"), type));
            return result;
        };
        return response(events.findAll(spec, pageable(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")))).map(this::eventInfo));
    }

    @Transactional
    public EventInfo processEvent(long id, String adminLogin, String memo) {
        Account admin = admin(adminLogin);
        String normalizedMemo = memo.trim();
        if (normalizedMemo.isEmpty() || normalizedMemo.length() > 500)
            throw ApiException.badRequest("처리 메모는 공백을 제외하고 1~500자여야 합니다.");
        SafetyEvent current = findEvent(id);
        if (events.process(id, admin, now(), normalizedMemo) == 0) {
            throw ApiException.conflict("EVENT_ALREADY_PROCESSED", "이미 처리된 이벤트입니다.");
        }
        eventPublisher.publishEvent(AdminDataChangedEvent.event("event-processed", id, current.getWorker().getId()));
        return eventInfo(findEvent(id));
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        PageResponse<EventInfo> recent = events(null, null, null, 0, 10);
        return new Dashboard(accounts.countByRoleAndEnabledTrue(Role.WORKER), events.count(), events.countByProcessedAtIsNull(), recent.items());
    }

    @Transactional(readOnly = true)
    public Account admin(String loginId) {
        Account account = accounts.findByAdminLoginIdAndRole(loginId, Role.ADMIN).orElseThrow(ApiException::unauthorized);
        if (!account.isEnabled() || account.getDeletedAt() != null) throw ApiException.unauthorized();
        return account;
    }

    private WorkerProfile profile(long id) {
        if (id <= 0) throw ApiException.badRequest("작업자 ID를 확인하세요.");
        return workers.findById(id).filter(p -> p.getAccount().getRole() == Role.WORKER)
                .orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다."));
    }
    private WorkerProfile activeProfile(long id) {
        WorkerProfile profile = profile(id);
        if (!profile.getAccount().isEnabled() || profile.getAccount().getDeletedAt() != null) throw ApiException.notFound("WORKER_NOT_FOUND", "작업자 정보를 찾을 수 없습니다.");
        return profile;
    }
    private SafetyEvent findEvent(long id) {
        if (id <= 0) throw ApiException.badRequest("이벤트 ID를 확인하세요.");
        return events.findById(id).orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "이벤트를 찾을 수 없습니다."));
    }
    private WorkerInfo workerInfo(WorkerProfile p) { return new WorkerInfo(p.getId(), p.getWorkerName(), p.getSex(), p.getAge(), p.getAccount().isEnabled(), p.getAccount().getLoginLockedAt() != null, p.getAccount().getDeletedAt() != null, p.getMonitoringStatus(), p.getStatusReportedAt(), p.getLastSeenAt(), p.getWatchLastSeenAt(), p.getHeartRateStatus(), p.getHeartRateMeasuredAt(), HeartRateReportPolicy.freshness(p.getHeartRateMeasuredAt(), now())); }
    private EventInfo eventInfo(SafetyEvent e) { return new EventInfo(e.getId(), e.getWorker().getId(), e.getWorker().getWorkerName(), e.getOccurredAt(), e.getReceivedAt(), e.getEventType(), e.getAlertSender(), e.getProcessedAt(), e.getProcessedBy() == null ? null : e.getProcessedBy().getId(), e.getAdminMemo()); }
    private Pageable pageable(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) throw ApiException.badRequest("페이지 값을 확인하세요.");
        return PageRequest.of(page, size, sort);
    }
    private static <T> PageResponse<T> response(Page<T> p) { return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages()); }
    private Instant now() { return Instant.ofEpochMilli(clock.instant().toEpochMilli()); }
    private String randomString(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    public static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("비밀번호는 12~64자 및 UTF-8 72바이트 이하여야 합니다.");
    }
    private boolean matchesPassword(String password, String hash) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72 && encoder.matches(password, hash);
    }
}
