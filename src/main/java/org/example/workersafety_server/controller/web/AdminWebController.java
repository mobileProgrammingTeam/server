package org.example.workersafety_server.controller.web;

import org.example.workersafety_server.entity.EventType;
import org.example.workersafety_server.exception.ApiException;
import org.example.workersafety_server.service.SafetyService;
import org.example.workersafety_server.service.WeatherService;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminWebController {
    private final SafetyService service;
    private final WeatherService weatherService;

    public AdminWebController(SafetyService service, WeatherService weatherService) {
        this.service = service;
        this.weatherService = weatherService;
    }

    @GetMapping("/admin/login") public String login() { return "admin/login"; }
    @GetMapping("/admin/register") public String register() { return "redirect:/admin/workers#worker-create"; }
    @GetMapping("/admin/dashboard") public String dashboard(@RequestParam(required = false) String fragment,
                                                               HttpServletResponse response, Model model) {
        model.addAttribute("dashboard", service.dashboard());
        model.addAttribute("workersPage", service.workers(0, 10));
        return fragmentView(fragment, "live", "admin/dashboard :: dashboardLive", "admin/dashboard", response);
    }
    @GetMapping("/admin/events") public String events(@RequestParam(required = false) Boolean processed,
                                                       @RequestParam(required = false) Long workerId,
                                                       @RequestParam(required = false) EventType eventType,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(required = false) String fragment,
                                                       HttpServletResponse response,
                                                       Model model) {
        var eventsPage = service.events(processed, workerId, eventType, page, 20);
        page = validPage(page, eventsPage.totalPages());
        if (page != eventsPage.page()) eventsPage = service.events(processed, workerId, eventType, page, 20);
        model.addAttribute("eventsPage", eventsPage);
        model.addAttribute("processed", processed == null ? "" : processed.toString());
        model.addAttribute("workerId", workerId == null ? "" : workerId);
        model.addAttribute("eventType", eventType == null ? "" : eventType.name());
        model.addAttribute("page", page);
        return fragmentView(fragment, "list", "admin/events :: eventList", "admin/events", response);
    }
    @GetMapping("/admin/events/{eventId}/detail") public String eventDetail(@PathVariable long eventId,
                                                                             HttpServletResponse response, Model model) {
        model.addAttribute("event", service.event(eventId));
        response.setHeader("Cache-Control", "no-store");
        return "admin/fragments :: eventDetail";
    }
    @GetMapping("/admin/workers") public String workers(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(required = false) String fragment,
                                                          HttpServletResponse response,
                                                          Model model) {
        var workersPage = service.workers(page, 20);
        page = validPage(page, workersPage.totalPages());
        if (page != workersPage.page()) workersPage = service.workers(page, 20);
        model.addAttribute("workersPage", workersPage);
        model.addAttribute("page", page);
        return fragmentView(fragment, "list", "admin/workers :: workerList", "admin/workers", response);
    }
    @GetMapping("/admin/workers/{workerId}") public String worker(@PathVariable long workerId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(required = false) String fragment,
                                                                    HttpServletResponse response,
                                                                    Model model) {
        model.addAttribute("worker", service.worker(workerId));
        var eventsPage = service.events(null, workerId, null, page, 20);
        page = validPage(page, eventsPage.totalPages());
        if (page != eventsPage.page()) eventsPage = service.events(null, workerId, null, page, 20);
        model.addAttribute("eventsPage", eventsPage);
        model.addAttribute("page", page);
        return fragmentView(fragment, "live", "admin/worker-detail :: workerDetailLive", "admin/worker-detail", response);
    }
    @GetMapping("/admin/alerts") public String alerts(@RequestParam(required = false) String fragment,
                                                        HttpServletResponse response, Model model) {
        model.addAttribute("eventsPage", service.events(false, null, null, 0, 20));
        return fragmentView(fragment, "list", "admin/alerts :: alertList", "admin/alerts", response);
    }
    @GetMapping("/admin/zones") public String zones() { return "admin/zones"; }
    @GetMapping("/admin/zones/{zoneId}") public String zone() { return "admin/zone-detail"; }
    @GetMapping("/admin/connectivity") public String connectivity(Model model) {
        model.addAttribute("workersPage", service.workers(0, 100));
        return "admin/connectivity";
    }
    // Load weather for Thymeleaf and display a readable message if the provider fails.
    @GetMapping("/admin/weather") public String weather(Model model) {
        try {
            model.addAttribute("weather", weatherService.current());
        } catch (ApiException e) {
            model.addAttribute("weatherError", e.getMessage());
        }
        return "admin/weather";
    }
    @GetMapping("/admin/reports") public String reports(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(required = false) String fragment,
                                                          HttpServletResponse response, Model model) {
        var eventsPage = service.events(true, null, null, page, 20);
        page = validPage(page, eventsPage.totalPages());
        if (page != eventsPage.page()) eventsPage = service.events(true, null, null, page, 20);
        model.addAttribute("eventsPage", eventsPage);
        model.addAttribute("dashboard", service.dashboard());
        model.addAttribute("page", page);
        return fragmentView(fragment, "live", "admin/reports :: reportLive", "admin/reports", response);
    }
    @GetMapping("/admin/settings") public String settings() { return "admin/settings"; }
    @GetMapping("/admin/admins") public String admins(Authentication authentication, Model model) {
        model.addAttribute("adminLoginId", authentication.getName());
        return "admin/admins";
    }

    private int validPage(int requested, int totalPages) {
        return Math.min(requested, Math.max(totalPages - 1, 0));
    }

    private String fragmentView(String requested, String expected, String fragment, String page, HttpServletResponse response) {
        if (!expected.equals(requested)) return page;
        response.setHeader("Cache-Control", "no-store");
        return fragment;
    }
}
