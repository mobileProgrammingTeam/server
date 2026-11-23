package org.example.workersafety_server.controller.api;

import org.example.workersafety_server.dto.AdminUiModels.Weather;
import org.example.workersafety_server.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDomainApiController {
    private final WeatherService weatherService;

    public AdminDomainApiController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/weather/current")
    public Weather weather() {
        return weatherService.current();
    }
}
