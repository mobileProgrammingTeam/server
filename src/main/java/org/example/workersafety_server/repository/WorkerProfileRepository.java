package org.example.workersafety_server.repository;

import org.example.workersafety_server.entity.WorkerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerProfileRepository extends JpaRepository<WorkerProfile, Long> { }
