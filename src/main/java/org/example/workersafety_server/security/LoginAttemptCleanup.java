package org.example.workersafety_server.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class LoginAttemptCleanup {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LoginAttemptCleanup(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.login-rate-limit.cleanup-interval-ms:60000}")
    public void cleanupExpiredAttempts() {
        long cutoff = clock.millis() - LoginAttemptFilter.WINDOW_MILLIS;
        jdbc.update("DELETE FROM login_ip_attempts WHERE attempted_at <= ?", cutoff);
        jdbc.update("DELETE FROM login_ip_blocks WHERE blocked_until <= ? AND NOT EXISTS " +
                "(SELECT 1 FROM login_ip_attempts a WHERE a.ip_address = login_ip_blocks.ip_address AND a.attempted_at > ?)",
                clock.millis(), cutoff);
    }
}
