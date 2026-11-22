package org.example.workersafety_server;

import org.example.workersafety_server.entity.Role;
import org.example.workersafety_server.repository.AccountRepository;
import org.example.workersafety_server.service.SafetyService;
import org.example.workersafety_server.service.AdminEventStreamHub;
import org.example.workersafety_server.service.WeatherService;
import org.example.workersafety_server.controller.api.HealthController;
import org.example.workersafety_server.security.BearerTokenFilter;
import org.example.workersafety_server.security.LoginAttemptFilter;
import org.example.workersafety_server.security.RequestLogFilter;
import org.example.workersafety_server.security.AdminAuthenticationLog;
import org.example.workersafety_server.dto.ApiModels.Login;
import org.example.workersafety_server.exception.ApiException;
import org.example.workersafety_server.repository.WorkerProfileRepository;
import org.example.workersafety_server.repository.SafetyEventRepository;
import org.example.workersafety_server.repository.MobileTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class WorkersafetyServerApplicationTests {
    private static final String ADMIN = "admin-" + UUID.randomUUID();
    private static final String PASSWORD = "test-" + UUID.randomUUID();
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("workersafety_test")
            .withUsername("workersafety_test")
            .withPassword("workersafety_test");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired SafetyService service;
    @Autowired AdminEventStreamHub adminEventStreamHub;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.context.ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ADMIN_LOGIN_ID", () -> ADMIN);
        registry.add("ADMIN_INITIAL_PASSWORD", () -> PASSWORD);
    }

    @Test void contextLoads() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(accounts.existsByRole(Role.ADMIN)).isTrue();
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            assertThat(first.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            for (var connection : new java.sql.Connection[]{first, second}) {
                assertThat(connection.isValid(2)).isTrue();
            }
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO worker_profiles(worker_id,worker_name,sex,age) VALUES (?,?,?,?)", -1, "invalid", "U", 1)).isInstanceOf(Exception.class);
    }

    @Test void weatherReportsMissingSiteCoordinatesWithoutCallingProvider() {
        var weather = new WeatherService(json, "현장", "", "");
        assertThatThrownBy(weather::current)
                .isInstanceOf(ApiException.class)
                .hasMessage("현장 위도와 경도를 설정해 주세요.");
    }

    @Test void authenticationAndCsrfAreSeparated() throws Exception {
        mvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(get("/api/worker/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/register")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/login")).andExpect(status().isOk());
        MockHttpSession session = adminSession();
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/events").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/workers").session(session)).andExpect(status().isOk());
        int workersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM worker_profiles", Integer.class);
        mvc.perform(post("/api/admin/workers").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerName\":\"Anonymous\",\"sex\":\"U\",\"age\":25}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_profiles", Integer.class)).isEqualTo(workersBefore);
        mvc.perform(get("/admin/register").session(session)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/workers#worker-create"));
        mvc.perform(post("/api/admin/workers").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        long adminId = accounts.findByAdminLoginIdAndRole(ADMIN, Role.ADMIN).orElseThrow().getId();
        mvc.perform(post("/api/worker/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("workerId", adminId, "password", PASSWORD)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().is3xxRedirection());
    }

    @Test void adminSseRequiresSessionAndFragmentsRenderFromExistingQueries() throws Exception {
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stream").header("Authorization", "Bearer invalid-worker-token"))
                .andExpect(status().isUnauthorized());

        MockHttpSession admin = adminSession();
        mvc.perform(get("/api/admin/session").session(admin)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/admin/stream").session(admin))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Accel-Buffering", "no"));
        assertThat(adminEventStreamHub.connectionCount()).isEqualTo(1);
        adminEventStreamHub.closeSession(admin.getId());
        assertThat(adminEventStreamHub.connectionCount()).isZero();

        assertThat(mvc.perform(get("/admin/dashboard").param("fragment", "live").session(admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString()).contains("id=\"dashboard-live\"");
        assertThat(mvc.perform(get("/admin/alerts").param("fragment", "list").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("id=\"alert-list\"");
        assertThat(mvc.perform(get("/admin/events").param("fragment", "list").param("processed", "false").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("id=\"event-list\"");
        assertThat(mvc.perform(get("/admin/reports").param("fragment", "live").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("id=\"report-live\"");
        long workerId = createWorker(admin).get("workerId").asLong();
        assertThat(mvc.perform(get("/admin/workers/{id}", workerId).param("fragment", "live").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("id=\"worker-detail-live\"");
    }

    @Test void workerEventProcessingAndWithdrawalKeepHistory() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String password = created.get("temporaryPassword").asString(); String token = login(id, password);
        mvc.perform(get("/api/admin/events").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("time", "2026-01-01T09:00:00Z", "eventType", "FALL_DETECTED", "alertSender", "ACCELEROMETER"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.workerId").value(id))
                .andExpect(jsonPath("$.processedAt").isEmpty()).andExpect(jsonPath("$.adminMemo").isEmpty());
        JsonNode list = json.readTree(mvc.perform(get("/api/admin/events").session(admin).param("workerId", Long.toString(id)).param("processed", "false").param("eventType", "FALL_DETECTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andReturn().getResponse().getContentAsString());
        long eventId = list.get("items").get(0).get("eventId").asLong();
        mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminMemo\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("adminMemo", "메".repeat(501)))))
                .andExpect(status().isBadRequest());
        String memo = "현장 확인 완료. 작업자 이상 없음.";
        long adminId = accounts.findByAdminLoginIdAndRole(ADMIN, Role.ADMIN).orElseThrow().getId();
        String first = mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("adminMemo", memo))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processedBy").isNumber())
                .andExpect(jsonPath("$.processedBy").value(adminId))
                .andExpect(jsonPath("$.adminMemo").value(memo)).andReturn().getResponse().getContentAsString();
        mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminMemo\":\"다른 메모\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_ALREADY_PROCESSED"));
        assertThat(json.readTree(first).get("processedAt").isNull()).isFalse();
        mvc.perform(patch("/api/worker/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"workerName\":\"새 이름\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workerName").value("새 이름"));
        mvc.perform(delete("/api/worker/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", password)))).andExpect(status().isNoContent());
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events/{id}", eventId).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.workerName").value("탈퇴한 작업자"))
                .andExpect(jsonPath("$.adminMemo").value(memo));
        mvc.perform(post("/api/admin/workers/{id}/reset-password", id).session(admin).with(csrf())).andExpect(status().isNotFound());
    }

    // Regression coverage for administrator authorization, CSRF, repeat deletion and retained history.
    @Test void adminCanDeleteWorkerOnlyWithCsrfAndKeepHistory() throws Exception {
        MockHttpSession admin = adminSession();
        JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong();
        String password = created.get("temporaryPassword").asString();
        String token = login(id, password);
        mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("time", "2026-01-01T09:00:00Z",
                        "eventType", "FALL_DETECTED", "alertSender", "ACCELEROMETER"))))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/admin/workers/{id}", id).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/workers/{id}", id).session(admin)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/workers/{id}", id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/workers/{id}", id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/worker/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/worker/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("workerId", id, "password", password))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events").session(admin).param("workerId", Long.toString(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].workerName").value("탈퇴한 작업자"));
    }

    @Test void passwordChangesAndResetRevokeAllTokens() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String password = created.get("temporaryPassword").asString();
        String first = login(id, password), second = login(id, password), next = "new-" + UUID.randomUUID();
        mvc.perform(patch("/api/worker/me/password").header("Authorization", "Bearer " + first).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", password, "newPassword", next)))).andExpect(status().isNoContent());
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + second)).andExpect(status().isUnauthorized());
        String current = login(id, next);
        mvc.perform(post("/api/admin/workers/{id}/reset-password", id).session(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + current)).andExpect(status().isUnauthorized());
    }

    @Test void tokenExpiryLogoutAndInvalidRequests() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String password = created.get("temporaryPassword").asString();
        String one = login(id, password), two = login(id, password);
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + one)).andExpect(status().isNoContent());
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + one)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/worker/me").header("Authorization", "Bearer " + two).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + two).contentType(MediaType.APPLICATION_JSON)
                .content("{\"time\":\"bad-date\",\"eventType\":\"FALL_DETECTED\",\"alertSender\":\"sensor\"}")).andExpect(status().isBadRequest());
        long now = Instant.now().toEpochMilli();
        jdbc.update("UPDATE mobile_tokens SET issued_at=?, expires_at=? WHERE token_hash=?", now - 2000, now - 1000, SafetyService.hash(two));
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + two)).andExpect(status().isUnauthorized());
    }

    @Test void concurrentEventProcessingPreservesFirstResult() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String token = login(id, created.get("temporaryPassword").asString());
        String body = mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("time", "2026-01-01T09:00:00Z", "eventType", "ABNORMAL_VITAL", "alertSender", "sensor"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long eventId = json.readTree(body).get("eventId").asLong();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"adminMemo\":\"처리자 A 메모\"}")).andReturn().getResponse(); });
            var second = executor.submit(() -> { start.await(); return mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"adminMemo\":\"처리자 B 메모\"}")).andReturn().getResponse(); });
            start.countDown(); var one = first.get(15, TimeUnit.SECONDS); var two = second.get(15, TimeUnit.SECONDS);
            assertThat(one.getStatus()).isIn(200, 409, 503); assertThat(two.getStatus()).isIn(200, 409, 503);
            assertThat(one.getStatus() == 200 || two.getStatus() == 200).isTrue();
            assertThat(one.getStatus() == 200 && two.getStatus() == 200).isFalse();
            JsonNode saved = json.readTree(mvc.perform(get("/api/admin/events/{id}", eventId).session(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(saved.get("adminMemo").asString()).isIn("처리자 A 메모", "처리자 B 메모");
            for (var response : new org.springframework.mock.web.MockHttpServletResponse[]{one, two}) {
                if (response.getStatus() == 200) {
                    JsonNode result = json.readTree(response.getContentAsString());
                    assertThat(result.get("processedAt")).isEqualTo(saved.get("processedAt"));
                    assertThat(result.get("processedBy")).isEqualTo(saved.get("processedBy"));
                    assertThat(result.get("adminMemo")).isEqualTo(saved.get("adminMemo"));
                } else {
                    String code = json.readTree(response.getContentAsString()).get("code").asString();
                    assertThat(code).isEqualTo(response.getStatus() == 409
                            ? "EVENT_ALREADY_PROCESSED" : "DATABASE_BUSY");
                }
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void bootstrapDoesNotOverwriteExistingAdmin() throws Exception {
        long count = accounts.count();
        service.bootstrap(ADMIN, "different-" + UUID.randomUUID());
        assertThat(accounts.count()).isEqualTo(count);
        assertThat(adminSession()).isNotNull();
    }

    @Test void bcryptRejectsExtraBytesRatherThanTruncating() {
        String value = UUID.randomUUID().toString().repeat(2);
        String hash = encoder.encode(value);
        assertThat(encoder.matches(value, hash)).isTrue();
        assertThat(encoder.matches(value + "x", hash)).isFalse();
    }

    @Test void bearerFilterReturnsJsonWhenDatabaseIsLocked() throws Exception {
        var failingService = mock(SafetyService.class);
        when(failingService.authenticate(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "unavailable", new java.sql.SQLException("lock not available", "55P03")));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/worker/events");
        request.addHeader("Authorization", "Bearer " + UUID.randomUUID());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new BearerTokenFilter(failingService).doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("DATABASE_BUSY");
    }

    @Test void missingBootstrapSettingsAndUnavailableHealthAreReported() throws Exception {
        var accountRepo = mock(AccountRepository.class);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var isolated = new SafetyService(accountRepo, mock(WorkerProfileRepository.class), mock(SafetyEventRepository.class),
                mock(MobileTokenRepository.class), mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                Clock.systemUTC(), new org.springframework.transaction.support.TransactionTemplate(txManager), Duration.ofHours(12),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThatThrownBy(() -> isolated.bootstrap("", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_LOGIN_ID");
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("unavailable"));
        var response = new HealthController(unavailable).health();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
    }

    @Test void loginRacingPasswordResetCannotLeaveOldTokenValid() throws Exception {
        JsonNode created = createWorker(adminSession());
        long id = created.get("workerId").asLong(); String password = created.get("temporaryPassword").asString();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var loginResult = executor.submit(() -> {
                start.await();
                try { return service.login(new Login(id, password)).accessToken(); }
                catch (ApiException invalid) { return null; }
            });
            var reset = executor.submit(() -> { start.await(); return service.resetPassword(id); });
            start.countDown(); String token = loginResult.get(15, TimeUnit.SECONDS); reset.get(15, TimeUnit.SECONDS);
            if (token != null) assertThat(service.authenticate(token)).isNull();
        } finally { executor.shutdownNow(); }
    }

    @Test void lockedAccountRevokesTokensAndAdminResetUnlocksIt() throws Exception {
        JsonNode created = createWorker(adminSession());
        long id = created.get("workerId").asLong();
        String password = created.get("temporaryPassword").asString();
        String token = login(id, password);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login(new Login(id, "incorrect-password"))).isInstanceOf(ApiException.class);
        }
        String afterFive = service.login(new Login(id, password)).accessToken();
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> service.login(new Login(id, "incorrect-password"))).isInstanceOf(ApiException.class);
        }
        assertThat(service.authenticate(token)).isNull();
        assertThat(service.authenticate(afterFive)).isNull();
        assertThatThrownBy(() -> service.login(new Login(id, password))).isInstanceOf(ApiException.class);
        mvc.perform(post("/api/admin/workers/{id}/reset-password", id).session(adminSession()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").value("0000"));
        assertThat(service.login(new Login(id, "0000"))).isNotNull();
    }

    @Test void appAndAdminLoginShareIpLimitAndReturnRetryAfter() throws Exception {
        String ip = "192.0.2." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(200));
        mvc.perform(post("/admin/login").with(remoteIp(ip)).param("adminId", ADMIN).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/login").with(remoteIp(ip))).andExpect(status().isOk());
        for (int attempt = 0; attempt < 7; attempt++) {
            mvc.perform(post("/api/worker/auth/login").with(remoteIp(ip)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workerId\":999999,\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(post("/admin/login").with(remoteIp(ip)).with(csrf())
                            .param("adminId", ADMIN).param("password", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/api/worker/auth/login").with(remoteIp(ip)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":999999,\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test void legacyIpAttemptTableMigratesWithoutLosingRowsAndCanRunTwice() throws Exception {
        jdbc.execute("DROP TABLE login_ip_attempts");
        jdbc.execute("CREATE TABLE login_ip_attempts (ip_address TEXT NOT NULL, attempted_at BIGINT NOT NULL, PRIMARY KEY (ip_address, attempted_at))");
        jdbc.update("INSERT INTO login_ip_attempts(ip_address, attempted_at) VALUES (?, ?)", "2001:db8::1", Instant.now().toEpochMilli());
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-postgresql.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-postgresql.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_ip_attempts WHERE ip_address = ?", Integer.class, "2001:db8::1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_id FROM login_ip_attempts WHERE ip_address = ?", Long.class, "2001:db8::1")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'login_ip_attempts'::regclass AND contype = 'p'", Integer.class)).isEqualTo(1);
    }

    @Test void rateLimitHonorsWindowExpiryBlockExpiryAndConcurrentRequests() throws Exception {
        long base = 1_800_000_000_000L;
        String boundaryIp = "198.51.100.41";
        for (int attempt = 0; attempt < 10; attempt++) assertThat(rateAttempt(boundaryIp, base + attempt * 1000L).getStatus()).isEqualTo(200);
        assertThat(rateAttempt(boundaryIp, base + 180_000).getStatus()).isEqualTo(200);

        String blockedIp = "198.51.100.42";
        for (int attempt = 0; attempt < 11; attempt++) rateAttempt(blockedIp, base + attempt * 1000L);
        for (int second = 200; second <= 300; second += 10) {
            assertThat(rateAttempt(blockedIp, base + second * 1000L).getStatus()).isEqualTo(429);
        }
        assertThat(rateAttempt(blockedIp, base + 310_000).getStatus()).isEqualTo(200);

        String concurrentIp = "203.0.113.54";
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 12; i++) attempts.add(executor.submit(() -> {
                start.await();
                return rateAttempt(concurrentIp, base + 500_000).getStatus();
            }));
            start.countDown();
            int passed = 0;
            int limited = 0;
            for (var attempt : attempts) {
                int result = attempt.get(15, TimeUnit.SECONDS);
                if (result == 200) passed++;
                if (result == 429) limited++;
            }
            assertThat(passed).isEqualTo(10);
            assertThat(limited).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test void requestAndServiceLogsAreJsonAndShareRequestId() throws Exception {
        assertThat(context.getBeansOfType(RequestLogFilter.class)).hasSize(1);
        var requests = new ListAppender<ILoggingEvent>(); requests.start();
        var services = new ListAppender<ILoggingEvent>(); services.start();
        Logger requestLogger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(org.example.workersafety_server.service.ServiceCallLogger.class);
        requestLogger.addAppender(requests); serviceLogger.addAppender(services);
        try {
            mvc.perform(get("/api/admin/events").session(adminSession())).andExpect(status().isOk());
            JsonNode http = json.readTree(requests.list.get(requests.list.size() - 1).getFormattedMessage());
            JsonNode service = services.list.stream().map(ILoggingEvent::getFormattedMessage).map(line -> {
                try { return json.readTree(line); } catch (Exception ex) { throw new IllegalStateException(ex); }
            }).filter(node -> "SafetyService.events".equals(node.path("serviceMethod").asText())).findFirst().orElseThrow();
            assertThat(http.get("requestId").asString()).isNotBlank();
            assertThat(http.get("actorRole").asString()).isEqualTo("ADMIN");
            assertThat(service.get("requestId").asString()).isEqualTo(http.get("requestId").asString());
            assertThat(service.get("outcome").asString()).isEqualTo("SUCCESS");
        } finally {
            requestLogger.detachAppender(requests); serviceLogger.detachAppender(services);
            requests.stop(); services.stop();
        }
    }

    @Test void adminLoginAuditCapturesFailureSeparatelyFromHttpRedirect() throws Exception {
        var authentication = new ListAppender<ILoggingEvent>(); authentication.start();
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuthenticationLog.class);
        logger.addAppender(authentication);
        try {
            mvc.perform(post("/admin/login").with(remoteIp("192.0.2.199")).with(csrf())
                            .param("adminId", ADMIN).param("password", "wrong-password"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login?error"));
            JsonNode audit = json.readTree(authentication.list.get(authentication.list.size() - 1).getFormattedMessage());
            assertThat(audit.get("outcome").asString()).isEqualTo("FAILURE");
            assertThat(audit.get("attemptedId").asString()).isEqualTo(ADMIN);
        } finally { logger.detachAppender(authentication); authentication.stop(); }
    }

    private MockHttpServletResponse rateAttempt(String ip, long millis) throws Exception {
        var filter = new LoginAttemptFilter(jdbc, transactionManager,
                Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/worker/auth/login");
        request.setServletPath("/api/worker/auth/login");
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        return response;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

    @Test void eventSortingPaginationAndValidation() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String token = login(id, created.get("temporaryPassword").asString());
        for (String time : new String[]{"2026-01-01T08:00:00Z", "2026-01-01T09:00:00Z"}) {
            mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("time", time, "eventType", "DANGER_ZONE_APPROACH", "alertSender", "sensor"))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/admin/events").session(admin).param("workerId", Long.toString(id)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].time").value("2026-01-01T09:00:00Z"));
        mvc.perform(get("/api/admin/events").session(admin).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/worker/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"age\":null}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/worker/me/password").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", UUID.randomUUID().toString(), "newPassword", "new-" + UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }

    @Test void adminDesignPagesRenderAndDesignApisReturnFixedValuesWithoutWriting() throws Exception {
        MockHttpSession admin = adminSession();
        int workersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM worker_profiles", Integer.class);
        int eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM safety_events", Integer.class);

        mvc.perform(get("/api/admin/dashboard").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.workers.length()").value(4));
        mvc.perform(get("/api/admin/workers/status").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].workerId").value(101));
        for (String path : new String[]{"/admin/dashboard", "/admin/events", "/admin/workers",
                "/admin/alerts", "/admin/zones", "/admin/connectivity", "/admin/weather",
                "/admin/reports", "/admin/settings", "/admin/admins"}) {
            mvc.perform(get(path).session(admin)).andExpect(status().isOk());
        }
        String workerPage = mvc.perform(get("/admin/workers").session(admin)).andReturn().getResponse().getContentAsString();
        assertThat(workerPage).contains("+ 작업자 생성", "id=\"worker-create\"", "name=\"age\"", "등록된 작업자가 없습니다.");
        assertThat(workerPage).doesNotContain("김민준", "A 작업구역", "sample@example.invalid");
        String workerList = mvc.perform(get("/admin/workers").param("fragment", "list").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(workerList).contains("id=\"worker-list\"").doesNotContain("id=\"worker-form\"");
        for (String path : new String[]{"/admin/dashboard", "/admin/alerts", "/admin/zones", "/admin/connectivity",
                "/admin/weather", "/admin/reports", "/admin/settings", "/admin/admins"}) {
            String html = mvc.perform(get(path).session(admin)).andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain("김민준", "A 작업구역", "example.invalid", "33°C", "최도윤");
        }
        mvc.perform(post("/api/admin/weather/notices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"sample\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notice.id").value(4002));
        mvc.perform(post("/api/worker/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerName\":\"Sample\",\"email\":\"sample@example.invalid\",\"password\":\"password-123\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_profiles", Integer.class)).isEqualTo(workersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_events", Integer.class)).isEqualTo(eventsBefore);
    }

    private MockHttpSession adminSession() throws Exception {
        return (MockHttpSession) mvc.perform(post("/admin/login").with(remoteIp("192.0.2." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(200))))
                        .with(csrf()).param("adminId", ADMIN).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/dashboard")).andReturn().getRequest().getSession(false);
    }
    private JsonNode createWorker(MockHttpSession admin) throws Exception {
        String result = mvc.perform(post("/api/admin/workers").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("workerName", "테스트 작업자", "sex", "U", "age", 25))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(result);
    }
    private String login(long id, String password) throws Exception {
        String result = mvc.perform(post("/api/worker/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(remoteIp("198.51.100." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(200))))
                .content(json.writeValueAsString(Map.of("workerId", id, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("accessToken").asString();
    }
}
