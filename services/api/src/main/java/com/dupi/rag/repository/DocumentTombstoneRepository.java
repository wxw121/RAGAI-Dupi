package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.DocumentTombstone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentTombstoneRepository extends JpaRepository<DocumentTombstone, UUID> {
}
