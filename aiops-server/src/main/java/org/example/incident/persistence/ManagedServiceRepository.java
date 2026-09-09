package org.example.incident.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedServiceRepository extends JpaRepository<ManagedServiceRecord, String> {
}
