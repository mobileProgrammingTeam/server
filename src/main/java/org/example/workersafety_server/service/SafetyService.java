package org.example.workersafety_server.service;

import jakarta.annotation.PostConstruct;
import org.example.workersafety_server.dto.ApiModels.*;
import org.example.workersafety_server.entity.*;
import org.example.workersafety_server.exception.ApiException;
import org.example.workersafety_server.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final SecureRandom random = new SecureRandom();
    private final Object credentialLock = new Object();

    public SafetyService(AccountRepository accounts, WorkerProfileRepository workers, SafetyEventRepository events,
                         MobileTokenRepository tokens, PasswordEncoder encoder, Clock clock, TransactionTemplate transactions,
                         @Value("${app.token-ttl:PT12H}") Duration tokenTtl) {
        this.accounts = accounts; this.workers = workers; this.events = events; this.tokens = tokens;
        this.encoder = encoder; this.clock = clock; this.transactions = transactions; this.tokenTtl = tokenTtl;
    }

    @PostConstruct
    void validateConfiguration() {
        if (tokenTtl.compareTo(Duration.ofMillis(1)) < 0) throw new IllegalStateException("MOBILE_TOKEN_TTL must be at least 1ms");
    }

    public void bootstrap(String loginId, String password) {
        synchronized (credentialLock) {
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
    }

    @Transactional
    public WorkerCreated createWorker(NewWorker request) {
        String password = randomString(18);
        Account account = new Account();
        account.setRole(Role.WORKER); account.setEnabled(true); account.setPasswordHash(encoder.encode(password));
        account.setCreatedAt(now()); account.setUpdatedAt(now());
        accounts.saveAndFlush(account);
        WorkerProfile profile = new WorkerProfile();
        profile.setAccount(account); profile.setWorkerName(request.workerName().trim());
        profile.setSex(request.sex()); profile.setAge(request.age());
        workers.saveAndFlush(profile);
        return new WorkerCreated(account.getId(), password);
    }

    public TokenResponse login(Login request) {
        synchronized (credentialLock) {
            return transactions.execute(status -> {
                Account account = accounts.findById(request.workerId()).orElseThrow(ApiException::unauthorized);
                if (account.getRole() != Role.WORKER || !account.isEnabled() || account.getDeletedAt() != null
                        || !matchesPassword(request.password(), account.getPasswordHash())) throw ApiException.unauthorized();
                String raw = randomString(32);
                Instant issued = now();
                MobileToken token = new MobileToken();
                token.setTokenHash(hash(raw)); token.setAccount(account); token.setIssuedAt(issued); token.setExpiresAt(issued.plus(tokenTtl));
                tokens.save(token);
                return new TokenResponse(raw, "Bearer", token.getExpiresAt());
            });
        }
    }

    @Transactional(readOnly = true)
    public Long authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() > 256 || rawToken.isBlank()) return null;
        return tokens.findById(hash(rawToken)).filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(now()))
                .filter(t -> t.getAccount().isEnabled() && t.getAccount().getDeletedAt() == null && t.getAccount().getRole() == Role.WORKER)
                .map(t -> t.getAccount().getId()).orElse(null);
    }

    @Transactional
    public void logout(String rawToken) { tokens.revoke(hash(rawToken), now()); }

    @Transactional(readOnly = true)
    public WorkerInfo worker(long id) { return workerInfo(profile(id)); }

    @Transactional(readOnly = true)
    public PageResponse<WorkerInfo> workers(int page, int size) {
        return response(workers.findAll(pageable(page, size, Sort.by("id"))).map(this::workerInfo));
    }

    @Transactional
    public WorkerInfo updateProfile(long id, Map<String, Object> changes) {
        if (changes.isEmpty() || !Set.of("workerName", "sex", "age").containsAll(changes.keySet()) || changes.containsValue(null))
            throw ApiException.badRequest("수정할 개인정보를 확인하세요.");
        WorkerProfile profile = activeProfile(id);
        if (changes.containsKey("workerName")) {
            Object value = changes.get("workerName");
            if (!(value instanceof String name) || name.isBlank() || name.length() > 50) throw ApiException.badRequest("이름을 확인하세요.");
            profile.setWorkerName(name.trim());
        }
        if (changes.containsKey("sex")) {
            Object value = changes.get("sex");
            if (!(value instanceof String sex) || !Set.of("M", "F", "U").contains(sex)) throw ApiException.badRequest("성별을 확인하세요.");
            profile.setSex(sex);
        }
        if (changes.containsKey("age")) {
            Object value = changes.get("age");
            if (!(value instanceof Integer age) || age < 0 || age > 150) throw ApiException.badRequest("나이를 확인하세요.");
            profile.setAge(age);
        }
        profile.getAccount().setUpdatedAt(now());
        return workerInfo(profile);
    }

    public void changePassword(long id, PasswordChange request) {
        validatePassword(request.newPassword());
        synchronized (credentialLock) {
            transactions.executeWithoutResult(status -> {
                Account account = activeProfile(id).getAccount();
                if (!matchesPassword(request.currentPassword(), account.getPasswordHash())) throw ApiException.unauthorized();
                account.setPasswordHash(encoder.encode(request.newPassword())); account.setUpdatedAt(now());
                tokens.revokeAll(id, now());
            });
        }
    }

    public WorkerCreated resetPassword(long id) {
        synchronized (credentialLock) {
            return transactions.execute(status -> {
                Account account = activeProfile(id).getAccount();
                String password = randomString(18);
                account.setPasswordHash(encoder.encode(password)); account.setUpdatedAt(now());
                tokens.revokeAll(id, now());
                return new WorkerCreated(id, password);
            });
        }
    }

    public void deleteAccount(long id, PasswordConfirmation request) {
        synchronized (credentialLock) {
            transactions.executeWithoutResult(status -> {
                WorkerProfile profile = activeProfile(id);
                Account account = profile.getAccount();
                if (!matchesPassword(request.currentPassword(), account.getPasswordHash())) throw ApiException.unauthorized();
                Instant time = now();
                account.setEnabled(false); account.setDeletedAt(time); account.setUpdatedAt(time);
                account.setPasswordHash(encoder.encode(randomString(32)));
                profile.setWorkerName("탈퇴한 작업자"); profile.setSex(null); profile.setAge(null);
                tokens.revokeAll(id, time);
            });
        }
    }

    @Transactional
    public EventInfo createEvent(long id, NewEvent request) {
        SafetyEvent event = new SafetyEvent();
        event.setWorker(activeProfile(id)); event.setOccurredAt(Instant.ofEpochMilli(request.time().toEpochMilli())); event.setReceivedAt(now());
        event.setEventType(request.eventType()); event.setAlertSender(request.alertSender().trim());
        return eventInfo(events.saveAndFlush(event));
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
        if (events.process(id, admin, now(), normalizedMemo) == 0) {
            findEvent(id);
            throw ApiException.conflict("EVENT_ALREADY_PROCESSED", "이미 처리된 이벤트입니다.");
        }
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
    private WorkerInfo workerInfo(WorkerProfile p) { return new WorkerInfo(p.getId(), p.getWorkerName(), p.getSex(), p.getAge(), p.getAccount().isEnabled()); }
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
