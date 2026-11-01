package org.example.workersafety_server.config;

import org.example.workersafety_server.service.SafetyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final SafetyService service;
    private final String login;
    private final String password;
    public AdminBootstrap(SafetyService service, @Value("${ADMIN_LOGIN_ID:}") String login,
                          @Value("${ADMIN_INITIAL_PASSWORD:}") String password) {
        this.service = service; this.login = login; this.password = password;
    }
    @Override public void run(ApplicationArguments args) { service.bootstrap(login, password); }
}
