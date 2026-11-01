package org.example.workersafety_server.controller.api;

import jakarta.validation.Valid;
import org.example.workersafety_server.dto.ApiModels.*;
import org.example.workersafety_server.service.SafetyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/worker")
public class WorkerController {
    private final SafetyService service;
    public WorkerController(SafetyService service) { this.service = service; }

    @PostMapping("/auth/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody Login request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.login(request));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        service.logout((String) authentication.getCredentials()); return ResponseEntity.noContent().build();
    }

    @PostMapping("/events")
    public ResponseEntity<EventInfo> event(Authentication authentication, @Valid @RequestBody NewEvent request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEvent(id(authentication), request));
    }

    @PatchMapping("/me")
    public WorkerInfo update(Authentication authentication, @RequestBody Map<String, Object> changes) { return service.updateProfile(id(authentication), changes); }

    @PatchMapping("/me/password")
    public ResponseEntity<Void> password(Authentication authentication, @Valid @RequestBody PasswordChange request) {
        service.changePassword(id(authentication), request); return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(Authentication authentication, @Valid @RequestBody PasswordConfirmation request) {
        service.deleteAccount(id(authentication), request); return ResponseEntity.noContent().build();
    }
    private long id(Authentication authentication) { return (Long) authentication.getPrincipal(); }
}
