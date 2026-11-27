package org.example.workersafety_server.service;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.example.workersafety_server.service.event.AdminDataChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AdminEventStreamHub {
    private static final Logger log = LoggerFactory.getLogger(AdminEventStreamHub.class);
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> sessionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> principalCounts = new ConcurrentHashMap<>();
    private final Executor sender;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper json;
    private final int maxPerSession;
    private final int maxPerInstance;
    private final int maxPendingPerConnection;
    private final long timeoutMillis;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger connectionCount = new AtomicInteger();

    public AdminEventStreamHub(@Qualifier("adminSseSender") Executor sender,
                               @Qualifier("adminSseScheduler") ScheduledExecutorService scheduler,
                               ObjectMapper json,
                               @Value("${app.admin-sse.max-per-session:8}") int maxPerSession,
                               @Value("${app.admin-sse.max-connections:256}") int maxPerInstance,
                               @Value("${app.admin-sse.max-pending-per-connection:64}") int maxPendingPerConnection,
                               @Value("${app.admin-sse.timeout:PT5M}") java.time.Duration timeout,
                               @Value("${app.admin-sse.heartbeat:PT25S}") java.time.Duration heartbeat) {
        this.sender = sender;
        this.scheduler = scheduler;
        this.json = json;
        this.maxPerSession = maxPerSession;
        this.maxPerInstance = maxPerInstance;
        this.maxPendingPerConnection = maxPendingPerConnection;
        this.timeoutMillis = timeout.toMillis();
        scheduler.scheduleAtFixedRate(this::heartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }

    public SseEmitter open(String principal, HttpSession session) {
        if (closing.get()) throw new IllegalStateException("SSE service is shutting down");
        String sessionId = session.getId();
        int current = increment(sessionCounts, sessionId);
        int principalCurrent = increment(principalCounts, principal);
        int instanceCurrent = connectionCount.incrementAndGet();
        if (current > maxPerSession || principalCurrent > maxPerSession || instanceCurrent > maxPerInstance || closing.get()) {
            decrement(sessionCounts, sessionId);
            decrement(principalCounts, principal);
            connectionCount.decrementAndGet();
            return null;
        }

        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Connection connection = new Connection(id, principal, sessionId, emitter);
        connections.put(id, connection);
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> remove(connection));
        emitter.onError(error -> remove(connection));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().reconnectTime(3000).comment("retry"));
        } catch (IOException | IllegalStateException ex) {
            remove(connection);
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    public void publish(AdminDataChangedEvent event) {
        Map<String, Object> data = event.eventId() == null
                ? Map.of("workerId", event.workerId())
                : Map.of("eventId", event.eventId(), "workerId", event.workerId());
        String payload;
        try {
            payload = json.writeValueAsString(data);
        } catch (Exception ex) {
            log.warn("Could not serialize administrator update event");
            return;
        }
        for (Connection connection : connections.values()) {
            if (!connection.enqueue(event.type(), payload)) {
                log.warn("Closing slow administrator SSE connection {}", connection.id);
                remove(connection);
                connection.emitter.complete();
            }
        }
    }

    public void closeSession(String sessionId) {
        for (Connection connection : connections.values()) {
            if (connection.sessionId.equals(sessionId)) {
                remove(connection);
                connection.emitter.complete();
            }
        }
    }

    public int connectionCount() { return connectionCount.get(); }

    private void heartbeat() {
        for (Connection connection : connections.values()) {
            if (!connection.enqueue(null, null)) {
                remove(connection);
                connection.emitter.complete();
            }
        }
    }

    private void remove(Connection connection) {
        if (!connections.remove(connection.id, connection)) return;
        connection.stop();
        connectionCount.decrementAndGet();
        decrement(sessionCounts, connection.sessionId);
        decrement(principalCounts, connection.principal);
    }

    private int increment(ConcurrentHashMap<String, Integer> counts, String key) {
        return counts.compute(key, (ignored, value) -> value == null ? 1 : value + 1);
    }

    private void decrement(ConcurrentHashMap<String, Integer> counts, String key) {
        counts.computeIfPresent(key, (ignored, value) -> value <= 1 ? null : value - 1);
    }

    @PreDestroy
    void shutdown() {
        closing.set(true);
        for (Connection connection : new ArrayList<>(connections.values())) {
            remove(connection);
            connection.emitter.complete();
        }
    }

    private final class Connection {
        private final String id;
        private final String principal;
        private final String sessionId;
        private final SseEmitter emitter;
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Connection(String id, String principal, String sessionId, SseEmitter emitter) {
            this.id = id;
            this.principal = principal;
            this.sessionId = sessionId;
            this.emitter = emitter;
        }

        private boolean enqueue(String type, String payload) {
            if (!active.get()) return true;
            if (pending.incrementAndGet() > maxPendingPerConnection) {
                pending.decrementAndGet();
                return false;
            }
            try {
                sender.execute(() -> {
                    try {
                        if (active.get()) {
                            if (type == null) emitter.send(SseEmitter.event().comment("heartbeat"));
                            else emitter.send(SseEmitter.event().name(type).data(payload, MediaType.APPLICATION_JSON));
                        }
                    } catch (IOException | IllegalStateException ex) {
                        remove(this);
                        emitter.completeWithError(ex);
                    } finally {
                        pending.decrementAndGet();
                    }
                });
                return true;
            } catch (RejectedExecutionException ex) {
                pending.decrementAndGet();
                return false;
            }
        }

        private void stop() { active.set(false); }
    }
}
