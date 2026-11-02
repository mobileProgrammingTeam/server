package org.example.workersafety_server.controller.api;

import jakarta.validation.Valid;
import org.example.workersafety_server.dto.ApiModels.*;
import org.example.workersafety_server.entity.EventType;
import org.example.workersafety_server.service.SafetyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {
    private final SafetyService service;
    public AdminApiController(SafetyService service) { this.service = service; }

    @GetMapping("/events")
    public PageResponse<EventInfo> events(@RequestParam(required = false) Boolean processed,
                                          @RequestParam(required = false) Long workerId,
                                          @RequestParam(required = false) EventType eventType,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.events(processed, workerId, eventType, page, size);
    }
    @GetMapping("/events/{eventId}")
    public EventInfo event(@PathVariable long eventId) { return service.event(eventId); }
    @PatchMapping("/events/{eventId}/process")
    public EventInfo process(@PathVariable long eventId, @Valid @RequestBody ProcessEventRequest request,
                             Authentication admin) {
        return service.processEvent(eventId, admin.getName(), request.adminMemo());
    }
    @PostMapping("/workers")
    public ResponseEntity<WorkerCreated> create(@Valid @RequestBody NewWorker request) {
        WorkerCreated created = service.createWorker(request);
        return ResponseEntity.created(URI.create("/api/admin/workers/" + created.workerId())).cacheControl(CacheControl.noStore()).body(created);
    }
    @GetMapping("/workers")
    public PageResponse<WorkerInfo> workers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.workers(page, size);
    }
    @GetMapping("/workers/{workerId}")
    public WorkerInfo worker(@PathVariable long workerId) { return service.worker(workerId); }
    @PostMapping("/workers/{workerId}/reset-password")
    public ResponseEntity<WorkerCreated> reset(@PathVariable long workerId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resetPassword(workerId));
    }
}
