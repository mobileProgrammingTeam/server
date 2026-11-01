package org.example.workersafety_server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "safety_events") @Getter @Setter @NoArgsConstructor
public class SafetyEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "event_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "worker_id") private WorkerProfile worker;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false) private EventType eventType;
    @Column(name = "alert_sender", nullable = false) private String alertSender;
    @Column(name = "processed_at") private Instant processedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "processed_by") private Account processedBy;
}
