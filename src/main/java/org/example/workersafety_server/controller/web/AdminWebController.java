package org.example.workersafety_server.controller.web;

import org.example.workersafety_server.service.SafetyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminWebController {
    private final SafetyService service;
    public AdminWebController(SafetyService service) { this.service = service; }

    @GetMapping("/admin/login") public String login() { return "admin/login"; }
    @GetMapping("/admin/dashboard") public String dashboard(Model model) {
        model.addAttribute("dashboard", service.dashboard()); return "admin/dashboard";
    }
    @GetMapping("/admin/events") public String events() { return "admin/events"; }
    @GetMapping("/admin/workers") public String workers() { return "admin/workers"; }
}
