package org.example.workersafety_server.security;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.example.workersafety_server.service.AdminEventStreamHub;

public class AdminSseSessionListener implements HttpSessionListener {
    private final AdminEventStreamHub hub;

    public AdminSseSessionListener(AdminEventStreamHub hub) { this.hub = hub; }

    @Override public void sessionDestroyed(HttpSessionEvent event) {
        hub.closeSession(event.getSession().getId());
    }
}
