package com.banking.account_service.repository;

import com.banking.account_service.model.BalanceOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BalanceOperationRepository extends JpaRepository<BalanceOperation, Long> {
    boolean existsByOperationKey(String operationKey);
    Optional<BalanceOperation> findByOperationKey(String operationKey);
}
