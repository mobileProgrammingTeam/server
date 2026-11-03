package org.example.workersafety_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DatabaseConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
