package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRoleCodeAndDisabledFalse(String roleCode);
}
