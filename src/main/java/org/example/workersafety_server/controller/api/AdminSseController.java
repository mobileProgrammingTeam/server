package org.example.workersafety_server.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.example.workersafety_server.service.AdminEventStreamHub;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AdminSseController {
    private final AdminEventStreamHub hub;

    public AdminSseController(AdminEventStreamHub hub) { this.hub = hub; }

    @GetMapping("/api/admin/session")
    public ResponseEntity<Void> session() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(value = "/api/admin/stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> stream(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null || authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build();
        }
        SseEmitter emitter = hub.open(authentication.getName(), session);
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                    .header("Retry-After", "10").body(null);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}
