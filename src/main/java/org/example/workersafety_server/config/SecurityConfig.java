package org.example.workersafety_server.config;

import jakarta.servlet.http.HttpServletResponse;
import org.example.workersafety_server.exception.ApiException;
import org.example.workersafety_server.security.BearerTokenFilter;
import org.example.workersafety_server.service.SafetyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.example.workersafety_server.security.LoginAttemptFilter;
import org.example.workersafety_server.security.RequestLogFilter;
import org.example.workersafety_server.security.AdminAuthenticationLog;
import org.example.workersafety_server.security.ActorCaptureFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        return new PasswordEncoder() {
            @Override public String encode(CharSequence rawPassword) { return bcrypt.encode(rawPassword); }
            @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword != null && rawPassword.toString().getBytes(StandardCharsets.UTF_8).length <= 72
                        && bcrypt.matches(rawPassword, encodedPassword);
            }
        };
    }

    @Bean UserDetailsService userDetailsService(SafetyService service) {
        return name -> {
            try {
                var account = service.admin(name);
                return User.withUsername(account.getAdminLoginId()).password(account.getPasswordHash()).roles("ADMIN").build();
            } catch (ApiException e) { throw new UsernameNotFoundException("Invalid credentials"); }
        };
    }

    @Bean @Order(1)
    SecurityFilterChain workerSecurity(HttpSecurity http, SafetyService service, JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) throws Exception {
        return http.securityMatcher("/api/worker/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/api/worker/auth/login").permitAll().anyRequest().hasRole("WORKER"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> json(response, 401))
                        .accessDeniedHandler((request, response, e) -> json(response, 403)))
                .addFilterBefore(new BearerTokenFilter(service), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new LoginAttemptFilter(jdbc, manager, clock), BearerTokenFilter.class)
                .addFilterBefore(new ActorCaptureFilter(), AuthorizationFilter.class)
                .build();
    }

    @Bean @Order(2)
    SecurityFilterChain adminSecurity(HttpSecurity http, JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                     AdminAuthenticationLog authenticationLog) throws Exception {
        var loginEntry = new LoginUrlAuthenticationEntryPoint("/admin/login");
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/css/**", "/js/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/admin/login").loginProcessingUrl("/admin/login")
                        .usernameParameter("adminId").passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            authenticationLog.success(request, authentication);
                            response.sendRedirect(request.getContextPath() + "/admin/dashboard");
                        })
                        .failureHandler((request, response, exception) -> {
                            authenticationLog.failure(request);
                            response.sendRedirect(request.getContextPath() + "/admin/login?error");
                        }).permitAll())
                .addFilterBefore(new LoginAttemptFilter(jdbc, manager, clock), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ActorCaptureFilter(), AuthorizationFilter.class)
                .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> {
                            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) json(response, 401);
                            else loginEntry.commence(request, response, e);
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                                var auth = SecurityContextHolder.getContext().getAuthentication();
                                json(response, auth == null || auth instanceof AnonymousAuthenticationToken ? 401 : 403);
                            } else response.sendError(403);
                        }))
                .httpBasic(basic -> basic.disable())
                .build();
    }

    private static void json(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(status == 401
                ? "{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"인증이 필요합니다.\"}"
                : "{\"code\":\"ACCESS_DENIED\",\"message\":\"접근 권한이 없습니다.\"}");
    }
}
