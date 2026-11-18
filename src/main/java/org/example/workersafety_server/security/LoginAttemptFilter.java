package org.example.workersafety_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;

public class LoginAttemptFilter extends OncePerRequestFilter {
    static final long WINDOW_MILLIS = 180_000;
    static final long BLOCK_MILLIS = 300_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LoginAttemptFilter(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !("POST".equalsIgnoreCase(request.getMethod()) &&
                ("/api/worker/auth/login".equals(path) || "/admin/login".equals(path)));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = normalizedIp(request.getRemoteAddr());
        long now = clock.millis();
        long retry = tx.execute(status -> {
            jdbc.update("INSERT INTO login_ip_blocks(ip_address, blocked_until) VALUES (?, 0) ON CONFLICT (ip_address) DO NOTHING", ip);
            Long blockedUntil = jdbc.queryForObject("SELECT blocked_until FROM login_ip_blocks WHERE ip_address = ? FOR UPDATE", Long.class, ip);
            if (blockedUntil != null && blockedUntil > now) return (blockedUntil - now + 999) / 1000;
            jdbc.update("UPDATE login_ip_blocks SET blocked_until = 0 WHERE ip_address = ? AND blocked_until <> 0", ip);
            jdbc.update("DELETE FROM login_ip_attempts WHERE ip_address = ? AND attempted_at <= ?", ip, now - WINDOW_MILLIS);
            jdbc.update("INSERT INTO login_ip_attempts(ip_address, attempted_at) VALUES (?, ?)", ip, now);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM login_ip_attempts WHERE ip_address = ? AND attempted_at > ?", Integer.class, ip, now - WINDOW_MILLIS);
            if (count != null && count > 10) {
                jdbc.update("UPDATE login_ip_blocks SET blocked_until = ? WHERE ip_address = ?", now + BLOCK_MILLIS, ip);
                return BLOCK_MILLIS / 1000;
            }
            return 0L;
        });
        if (retry > 0) {
            response.setStatus(429); response.setHeader("Retry-After", Long.toString(retry));
            if (request.getRequestURI().startsWith("/api/")) {
                response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":\"LOGIN_RATE_LIMITED\",\"message\":\"로그인 시도가 너무 많습니다.\"}");
            } else {
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.");
            }
            org.slf4j.LoggerFactory.getLogger(LoginAttemptFilter.class).info("Login rate limit rejected request from {}", ip);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String normalizedIp(String address) {
        try {
            return InetAddress.getByName(address).getHostAddress().replaceFirst("%.*$", "");
        } catch (UnknownHostException ex) {
            return address;
        }
    }
}
