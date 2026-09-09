package org.example.incident.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunbookRepository extends JpaRepository<RunbookRecord, String> {
    List<RunbookRecord> findByEnabledTrue();
}
