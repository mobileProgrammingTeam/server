package org.example.workersafety_server.service.event;

public record AdminDataChangedEvent(String type, Long eventId, Long workerId) {
    public static AdminDataChangedEvent event(String type, long eventId, long workerId) {
        return new AdminDataChangedEvent(type, eventId, workerId);
    }

    public static AdminDataChangedEvent worker(String type, long workerId) {
        return new AdminDataChangedEvent(type, null, workerId);
    }
}
