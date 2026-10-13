package com.flowpay.transaction.repository;

import com.flowpay.transaction.entity.TransferStatusChange;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferStatusChangeRepository extends JpaRepository<TransferStatusChange, UUID> {
}
