package com.flowpay.transaction.service;

import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.entity.Transfer;
import com.flowpay.transaction.entity.TransferStatus;
import com.flowpay.transaction.entity.TransferStatusChange;
import com.flowpay.transaction.repository.TransferRepository;
import com.flowpay.transaction.repository.TransferStatusChangeRepository;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a recorded transfer and gets the money moved, then records the result.
 * <p>
 * The rules that keep customers' money safe:
 * <ul>
 *   <li>The wallet service moves the money in one all-or-nothing step, so a transfer is either fully done or not done.</li>
 *   <li>The transfer reference is the wallet's reference, so asking twice moves the money once. That makes "the wallet
 *       service did not answer" safe to retry as often as needed.</li>
 *   <li>A transfer is marked FAILED only when the wallet service clearly refused it. A timeout or outage never fails
 *       it: it stays PROCESSING and the recovery sweep finishes it. Failing it by guess could hide a debit.</li>
 *   <li>Every state change is a conditional update plus an audit row in one transaction, so simultaneous attempts
 *       cannot double-process a transfer, and a FAILED transfer can never later become SUCCESS.</li>
 * </ul>
 */
@Service
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private static final String SYSTEM = "system";

    /** What a customer is told for each reason the wallet service can give. */
    private static final Map<String, String> MESSAGES = Map.of(
            "INSUFFICIENT_FUNDS", "Not enough available balance to cover the amount and the fee",
            "WALLET_NOT_ACTIVE", "Your wallet cannot send money right now",
            "RECIPIENT_NOT_ACCEPTING_FUNDS", "The recipient cannot receive money right now",
            "RECIPIENT_WALLET_NOT_FOUND", "The recipient's wallet could not be found",
            "SENDER_WALLET_NOT_FOUND", "Your wallet could not be found",
            "CURRENCY_MISMATCH", "The wallets do not hold this currency");

    private final TransferRepository transfers;
    private final TransferStatusChangeRepository history;
    private final WalletClient walletClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferProcessor(TransferRepository transfers, TransferStatusChangeRepository history, WalletClient walletClient,
                             TransactionTemplate tx, Clock clock) {
        this.transfers = transfers;
        this.history = history;
        this.walletClient = walletClient;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Asks the wallet service to move the money and records what it said. Returns the transfer as it now stands:
     * SUCCESS, FAILED, or still PROCESSING if the wallet service could not be reached. Safe to call any number of
     * times, from any number of requests at once. Never throws for a refusal; the caller reads the status.
     */
    public Transfer settle(Transfer transfer) {
        if (transfer.getStatus().isFinal()) {
            return transfer;
        }
        var outcome = walletClient.transfer(transfer.getReference(), transfer.getSenderWalletId(),
                transfer.getRecipientWalletId(), transfer.getAmount(), transfer.getFee(), transfer.getCurrency());
        switch (outcome.kind()) {
            case CREDITED -> {
                if (move(transfer, TransferStatus.SUCCESS, "Money moved", null, null)) {
                    log.info("Transfer succeeded reference={} amount={} fee={} {}", transfer.getReference(),
                            transfer.getAmount(), transfer.getFee(), transfer.getCurrency());
                }
            }
            case REFUSED -> {
                String reason = MESSAGES.getOrDefault(outcome.code(),
                        outcome.message() != null ? outcome.message() : "The transfer could not be completed");
                if (move(transfer, TransferStatus.FAILED, "Wallet refused: " + outcome.code(), outcome.code(), reason)) {
                    log.info("Transfer failed reference={} code={}", transfer.getReference(), outcome.code());
                }
            }
            case UNAVAILABLE -> log.warn("Transfer {} could not be confirmed yet; it stays PROCESSING and will be retried",
                    transfer.getReference());
        }
        // Another request may have moved it first. Report whatever state it is really in.
        return transfers.findById(transfer.getId()).orElse(transfer);
    }

    /** One legal state change: the conditional update and its audit row, together or not at all. */
    private boolean move(Transfer transfer, TransferStatus next, String detail, String failureCode, String failureReason) {
        TransferStatus from = transfer.getStatus();
        if (!from.canMoveTo(next)) {
            throw new IllegalStateException("Illegal transfer move " + from + " -> " + next);
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            var now = clock.instant();
            int changed = transfers.transition(transfer.getId(), from, next, now, failureCode, failureReason);
            if (changed == 1) {
                history.save(new TransferStatusChange(transfer.getId(), from, next, SYSTEM, detail, now));
            }
            return changed == 1;
        }));
    }
}
