package org.example.workersafety_server.config;

import org.example.workersafety_server.service.AdminEventStreamHub;
import org.example.workersafety_server.security.AdminSseSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AdminSseConfig implements WebMvcConfigurer {
    @Bean(name = "adminSseSender", destroyMethod = "shutdownNow")
    Executor adminSseSender() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2048),
                runnable -> new Thread(runnable, "admin-sse-send-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "adminSseScheduler", destroyMethod = "shutdownNow")
    ScheduledExecutorService adminSseScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("admin-sse-heartbeat-"));
    }

    @Bean AdminEventStreamHub adminEventStreamHub(@Qualifier("adminSseSender") Executor sender,
                                                  @Qualifier("adminSseScheduler") ScheduledExecutorService scheduler,
                                                  tools.jackson.databind.ObjectMapper json) {
        return new AdminEventStreamHub(sender, scheduler, json);
    }

    @Bean ServletListenerRegistrationBean<AdminSseSessionListener> adminSseSessionListener(AdminEventStreamHub hub) {
        return new ServletListenerRegistrationBean<>(new AdminSseSessionListener(hub));
    }

    @Bean(name = "mvcAsyncExecutor") ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("mvc-async-");
        return executor;
    }

    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(TimeUnit.MINUTES.toMillis(6));
        configurer.setTaskExecutor(mvcAsyncExecutor());
    }
}
