package org.example.workersafety_server.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

@RestController
public class HealthController {
    private final DataSource dataSource;
    public HealthController(DataSource dataSource) { this.dataSource = dataSource; }
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1")) {
            if (result.next()) return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (SQLException ignored) { }
        return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
}
