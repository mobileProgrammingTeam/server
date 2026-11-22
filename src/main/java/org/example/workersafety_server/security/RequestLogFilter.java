package org.example.workersafety_server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private final ObjectMapper json;

    public RequestLogFilter(ObjectMapper json) { this.json = json; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", id);
        request.setAttribute("requestId", id);
        try { chain.doFilter(request, response); }
        finally {
            long duration = (System.nanoTime() - start) / 1_000_000;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("requestId", id);
            entry.put("method", request.getMethod());
            entry.put("route", route(request));
            entry.put("actorRole", value(request, "actorRole", "ANONYMOUS"));
            entry.put("actorId", request.getAttribute("actorId"));
            if (request.getAttribute("attemptedId") != null) entry.put("attemptedId", request.getAttribute("attemptedId"));
            entry.put("httpStatus", response.getStatus());
            entry.put("durationMs", duration);
            try { log.info("{}", json.writeValueAsString(entry)); }
            catch (Exception ex) { log.warn("Request log serialization failed"); }
            MDC.remove("requestId");
        }
    }
    private String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route) return route;
        String path = request.getRequestURI();
        return path.replaceAll("/\\d+(?=/|$)", "/{id}");
    }

    private Object value(HttpServletRequest request, String key, Object fallback) {
        Object value = request.getAttribute(key);
        return value == null ? fallback : value;
    }
}
