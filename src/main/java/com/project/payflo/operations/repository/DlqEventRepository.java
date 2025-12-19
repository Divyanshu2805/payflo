package com.project.payflo.operations.repository;

import com.project.payflo.operations.entity.DlqEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DlqEventRepository extends JpaRepository<DlqEvent, UUID> {
}
