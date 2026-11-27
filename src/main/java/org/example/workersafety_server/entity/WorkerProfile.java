package org.example.workersafety_server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "worker_profiles") @Getter @Setter @NoArgsConstructor
public class WorkerProfile {
    @Id @Column(name = "worker_id") private Long id;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "worker_id") private Account account;
    @Column(name = "worker_name", nullable = false) private String workerName;
    private String sex;
    private Integer age;
    @Enumerated(EnumType.STRING) @Column(name = "monitoring_status")
    private MonitoringStatus monitoringStatus;
    @Column(name = "status_reported_at")
    private java.time.Instant statusReportedAt;
    @Column(name = "last_seen_at")
    private java.time.Instant lastSeenAt;
    @Column(name = "watch_last_seen_at")
    private java.time.Instant watchLastSeenAt;
    @Enumerated(EnumType.STRING) @Column(name = "heart_rate_status")
    private HeartRateStatus heartRateStatus;
    @Column(name = "heart_rate_measured_at")
    private java.time.Instant heartRateMeasuredAt;
}
