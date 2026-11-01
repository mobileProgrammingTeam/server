package org.example.workersafety_server;

import org.example.workersafety_server.entity.Role;
import org.example.workersafety_server.repository.AccountRepository;
import org.example.workersafety_server.service.SafetyService;
import org.example.workersafety_server.controller.api.HealthController;
import org.example.workersafety_server.security.BearerTokenFilter;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkersafetyServerApplicationTests {
    private static final String ADMIN = "admin-" + UUID.randomUUID();
    private static final String PASSWORD = "test-" + UUID.randomUUID();
    private static final String DB_PATH = Path.of(System.getProperty("java.io.tmpdir"), "workersafety-test-" + UUID.randomUUID() + ".db").toString();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired SafetyService service;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db-path", () -> DB_PATH);
        registry.add("ADMIN_LOGIN_ID", () -> ADMIN);
        registry.add("ADMIN_INITIAL_PASSWORD", () -> PASSWORD);
    }

    @Test void contextLoads() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(accounts.existsByRole(Role.ADMIN)).isTrue();
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            for (var connection : new java.sql.Connection[]{first, second}) {
                try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_keys")) {
                    assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(1);
                }
                try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA busy_timeout")) {
                    assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(5000);
                }
            }
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO worker_profiles(worker_id,worker_name,sex,age) VALUES (?,?,?,?)", -1, "invalid", "U", 1)).isInstanceOf(Exception.class);
    }

    @Test void authenticationAndCsrfAreSeparated() throws Exception {
        mvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(get("/api/worker/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/login")).andExpect(status().isOk());
        MockHttpSession session = adminSession();
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/events").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/workers").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/workers").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        long adminId = accounts.findByAdminLoginIdAndRole(ADMIN, Role.ADMIN).orElseThrow().getId();
        mvc.perform(post("/api/worker/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("workerId", adminId, "password", PASSWORD)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().is3xxRedirection());
    }

    @Test void workerEventProcessingAndWithdrawalKeepHistory() throws Exception {
        MockHttpSession admin = adminSession(); JsonNode created = createWorker(admin);
        long id = created.get("workerId").asLong(); String password = created.get("temporaryPassword").asString(); String token = login(id, password);
        mvc.perform(get("/api/admin/events").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/worker/events").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("time", "2026-01-01T09:00:00Z", "eventType", "FALL_DETECTED", "alertSender", "ACCELEROMETER"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.workerId").value(id)).andExpect(jsonPath("$.processedAt").isEmpty());
        JsonNode list = json.readTree(mvc.perform(get("/api/admin/events").session(admin).param("workerId", Long.toString(id)).param("processed", "false").param("eventType", "FALL_DETECTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andReturn().getResponse().getContentAsString());
        long eventId = list.get("items").get(0).get("eventId").asLong();
        String first = mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.processedBy").isNumber()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).get("processedAt")).isEqualTo(json.readTree(second).get("processedAt"));
        mvc.perform(patch("/api/worker/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"workerName\":\"새 이름\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workerName").value("새 이름"));
        mvc.perform(delete("/api/worker/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", password)))).andExpect(status().isNoContent());
        mvc.perform(post("/api/worker/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events/{id}", eventId).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.workerName").value("탈퇴한 작업자"));
        mvc.perform(post("/api/admin/workers/{id}/reset-password", id).session(admin).with(csrf())).andExpect(status().isNotFound());
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
            var first = executor.submit(() -> { start.await(); return mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())).andReturn().getResponse(); });
            var second = executor.submit(() -> { start.await(); return mvc.perform(patch("/api/admin/events/{id}/process", eventId).session(admin).with(csrf())).andReturn().getResponse(); });
            start.countDown(); var one = first.get(15, TimeUnit.SECONDS); var two = second.get(15, TimeUnit.SECONDS);
            assertThat(one.getStatus()).isIn(200, 503); assertThat(two.getStatus()).isIn(200, 503);
            assertThat(one.getStatus() == 200 || two.getStatus() == 200).isTrue();
            JsonNode saved = json.readTree(mvc.perform(get("/api/admin/events/{id}", eventId).session(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for (var response : new org.springframework.mock.web.MockHttpServletResponse[]{one, two}) {
                if (response.getStatus() == 200) {
                    JsonNode result = json.readTree(response.getContentAsString());
                    assertThat(result.get("processedAt")).isEqualTo(saved.get("processedAt"));
                    assertThat(result.get("processedBy")).isEqualTo(saved.get("processedBy"));
                } else assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("DATABASE_BUSY");
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
                "unavailable", new java.sql.SQLException("locked", null, 5)));
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
                Clock.systemUTC(), new org.springframework.transaction.support.TransactionTemplate(txManager), Duration.ofHours(12));
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

    private MockHttpSession adminSession() throws Exception {
        return (MockHttpSession) mvc.perform(post("/admin/login").with(csrf()).param("adminId", ADMIN).param("password", PASSWORD))
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
                .content(json.writeValueAsString(Map.of("workerId", id, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("accessToken").asString();
    }
}
