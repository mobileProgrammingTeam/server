package org.example.workersafety_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.workersafety_server.service.SafetyService;
import org.example.workersafety_server.exception.DatabaseErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

public class BearerTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private final SafetyService service;
    public BearerTokenFilter(SafetyService service) { this.service = service; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Long workerId;
            try { workerId = service.authenticate(token); }
            catch (DataAccessException e) {
                boolean busy = DatabaseErrors.isBusy(e);
                if (!busy) log.error("Token authentication database failure", e);
                response.setStatus(busy ? 503 : 500);
                response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                response.getWriter().write(busy
                        ? "{\"code\":\"DATABASE_BUSY\",\"message\":\"데이터베이스가 사용 중입니다.\"}"
                        : "{\"code\":\"INTERNAL_ERROR\",\"message\":\"요청을 처리하지 못했습니다.\"}");
                return;
            }
            if (workerId != null) {
                var auth = UsernamePasswordAuthenticationToken.authenticated(workerId, token, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
