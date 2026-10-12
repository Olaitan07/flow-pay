package com.flowpay.wallet.repository;

import com.flowpay.wallet.entity.WalletStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletStatusChangeRepository extends JpaRepository<WalletStatusChange, UUID> {

    List<WalletStatusChange> findByWalletIdOrderByCreatedAtAsc(UUID walletId);
}
