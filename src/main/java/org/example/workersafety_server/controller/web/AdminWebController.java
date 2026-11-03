package org.example.workersafety_server.controller.web;

import org.example.workersafety_server.entity.EventType;
import org.example.workersafety_server.service.SafetyService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminWebController {
    private final SafetyService service;
    public AdminWebController(SafetyService service) { this.service = service; }

    @GetMapping("/admin/login") public String login() { return "admin/login"; }
    @GetMapping("/admin/register") public String register() { return "redirect:/admin/workers#worker-create"; }
    @GetMapping("/admin/dashboard") public String dashboard(Model model) {
        model.addAttribute("dashboard", service.dashboard());
        model.addAttribute("workersPage", service.workers(0, 10));
        return "admin/dashboard";
    }
    @GetMapping("/admin/events") public String events(@RequestParam(required = false) Boolean processed,
                                                       @RequestParam(required = false) Long workerId,
                                                       @RequestParam(required = false) EventType eventType,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       Model model) {
        model.addAttribute("eventsPage", service.events(processed, workerId, eventType, page, 20));
        model.addAttribute("processed", processed == null ? "" : processed.toString());
        model.addAttribute("workerId", workerId == null ? "" : workerId);
        model.addAttribute("eventType", eventType == null ? "" : eventType.name());
        model.addAttribute("page", page);
        return "admin/events";
    }
    @GetMapping("/admin/events/{eventId}/detail") public String eventDetail(@PathVariable long eventId, Model model) {
        model.addAttribute("event", service.event(eventId));
        return "admin/fragments :: eventDetail";
    }
    @GetMapping("/admin/workers") public String workers(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(required = false) String fragment,
                                                          Model model) {
        model.addAttribute("workersPage", service.workers(page, 20));
        model.addAttribute("page", page);
        return "list".equals(fragment) ? "admin/workers :: workerList" : "admin/workers";
    }
    @GetMapping("/admin/workers/{workerId}") public String worker(@PathVariable long workerId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    Model model) {
        model.addAttribute("worker", service.worker(workerId));
        model.addAttribute("eventsPage", service.events(null, workerId, null, page, 20));
        model.addAttribute("page", page);
        return "admin/worker-detail";
    }
    @GetMapping("/admin/alerts") public String alerts(Model model) {
        model.addAttribute("eventsPage", service.events(false, null, null, 0, 20));
        return "admin/alerts";
    }
    @GetMapping("/admin/zones") public String zones() { return "admin/zones"; }
    @GetMapping("/admin/zones/{zoneId}") public String zone() { return "admin/zone-detail"; }
    @GetMapping("/admin/connectivity") public String connectivity(Model model) {
        model.addAttribute("workersPage", service.workers(0, 100));
        return "admin/connectivity";
    }
    @GetMapping("/admin/weather") public String weather() { return "admin/weather"; }
    @GetMapping("/admin/reports") public String reports(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("eventsPage", service.events(true, null, null, page, 20));
        model.addAttribute("dashboard", service.dashboard());
        model.addAttribute("page", page);
        return "admin/reports";
    }
    @GetMapping("/admin/settings") public String settings() { return "admin/settings"; }
    @GetMapping("/admin/admins") public String admins(Authentication authentication, Model model) {
        model.addAttribute("adminLoginId", authentication.getName());
        return "admin/admins";
    }
}
