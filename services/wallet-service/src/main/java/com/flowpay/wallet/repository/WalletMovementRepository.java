package com.flowpay.wallet.repository;

import com.flowpay.wallet.entity.WalletMovement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletMovementRepository extends JpaRepository<WalletMovement, UUID> {

    Optional<WalletMovement> findByReference(String reference);
}
