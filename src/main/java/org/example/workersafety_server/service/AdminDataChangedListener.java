package org.example.workersafety_server.service;

import org.example.workersafety_server.service.event.AdminDataChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class AdminDataChangedListener {
    private static final Logger log = LoggerFactory.getLogger(AdminDataChangedListener.class);
    private final AdminEventStreamHub hub;
    private final Executor sender;

    public AdminDataChangedListener(AdminEventStreamHub hub, @Qualifier("adminSseSender") Executor sender) {
        this.hub = hub;
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(AdminDataChangedEvent event) {
        try {
            sender.execute(() -> hub.publish(event));
        } catch (RejectedExecutionException ex) {
            log.warn("Administrator SSE broadcast queue is full");
        }
    }
}
