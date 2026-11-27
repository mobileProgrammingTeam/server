package org.example.workersafety_server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

@Component
public class AdminAuthenticationLog {
    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationLog.class);
    private final ObjectMapper json;

    public AdminAuthenticationLog(ObjectMapper json) { this.json = json; }

    public void success(HttpServletRequest request, Authentication authentication) {
        request.setAttribute("actorRole", "ADMIN");
        request.setAttribute("actorId", authentication.getName());
        write("SUCCESS", authentication.getName());
    }

    public void failure(HttpServletRequest request) {
        request.setAttribute("attemptedId", request.getParameter("adminId"));
        write("FAILURE", request.getParameter("adminId"));
    }

    private void write(String outcome, String attemptedId) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("requestId", org.slf4j.MDC.get("requestId"));
        entry.put("event", "ADMIN_AUTHENTICATION");
        entry.put("outcome", outcome);
        entry.put("attemptedId", attemptedId);
        try { log.info("{}", json.writeValueAsString(entry)); }
        catch (Exception ex) { log.warn("Admin authentication log serialization failed"); }
    }
}
