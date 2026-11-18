package org.example.workersafety_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration
@EnableScheduling
public class DatabaseConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
