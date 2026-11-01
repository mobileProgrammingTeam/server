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
}
