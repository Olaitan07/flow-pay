package com.flowpay.wallet.service;

import com.flowpay.wallet.dto.request.ChangeStatusRequest;
import com.flowpay.wallet.dto.response.WalletDetailResponse;
import com.flowpay.wallet.dto.response.WalletResponse;
import com.flowpay.wallet.entity.Wallet;
import com.flowpay.wallet.entity.WalletStatus;
import com.flowpay.wallet.entity.WalletStatusChange;
import com.flowpay.wallet.exception.WalletException;
import com.flowpay.wallet.repository.WalletRepository;
import com.flowpay.wallet.repository.WalletStatusChangeRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Operations staff freeze, block, reactivate and close wallets. Every change is recorded with who and why. */
@Service
public class WalletStatusService {

    private static final Logger log = LoggerFactory.getLogger(WalletStatusService.class);

    private final WalletRepository wallets;
    private final WalletStatusChangeRepository changes;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WalletStatusService(WalletRepository wallets, WalletStatusChangeRepository changes, TransactionTemplate tx, Clock clock) {
        this.wallets = wallets;
        this.changes = changes;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WalletDetailResponse detail(UUID walletId) {
        Wallet wallet = wallets.findById(walletId).orElseThrow(WalletException::notFound);
        var history = changes.findByWalletIdOrderByCreatedAtAsc(walletId).stream()
                .map(WalletDetailResponse.StatusChange::from).toList();
        return new WalletDetailResponse(WalletResponse.from(wallet), history);
    }

    public WalletDetailResponse change(UUID walletId, ChangeStatusRequest request, String actor) {
        Wallet current = wallets.findById(walletId).orElseThrow(WalletException::notFound);
        WalletStatus from = current.getStatus();
        WalletStatus to = request.status();
        if (from == to) {
            throw WalletException.rule("WALLET_STATUS_UNCHANGED", "The wallet is already " + from);
        }
        if (!from.canMoveTo(to)) {
            throw WalletException.rule("INVALID_STATUS_TRANSITION", "A " + from + " wallet cannot become " + to);
        }
        tx.executeWithoutResult(status -> {
            var now = clock.instant();
            int changed = to == WalletStatus.CLOSED
                    ? wallets.closeIfEmpty(walletId, from, now)
                    : wallets.changeStatus(walletId, from, to, now);
            if (changed == 0) {
                throw whyNotChanged(walletId, from, to);
            }
            changes.save(new WalletStatusChange(walletId, from, to, request.reason().trim(), actor, now));
        });
        log.info("Wallet status changed walletId={} {} -> {} actor={}", walletId, from, to, actor);
        return detail(walletId);
    }

    private WalletException whyNotChanged(UUID walletId, WalletStatus from, WalletStatus to) {
        Wallet now = wallets.findById(walletId).orElse(null);
        if (now == null) {
            return WalletException.notFound();
        }
        if (now.getStatus() != from) {
            return WalletException.conflict("WALLET_STATUS_CHANGED",
                    "The wallet changed to " + now.getStatus() + " while this request was running. Check and try again.");
        }
        return WalletException.rule("WALLET_NOT_EMPTY", "A wallet can only be closed when its balance is zero");
    }
}
