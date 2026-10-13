package com.flowpay.wallet.service;

import com.flowpay.wallet.dto.request.MovementRequest;
import com.flowpay.wallet.dto.response.MovementResponse;
import com.flowpay.wallet.entity.MovementType;
import com.flowpay.wallet.entity.Wallet;
import com.flowpay.wallet.entity.WalletMovement;
import com.flowpay.wallet.exception.WalletException;
import com.flowpay.wallet.repository.WalletMovementRepository;
import com.flowpay.wallet.repository.WalletRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts money into and takes money out of wallets. Funding and transfers (later epics) build on this.
 * <p>
 * The rules are enforced by one conditional UPDATE per movement, so they hold under any number of simultaneous
 * requests, and the database CHECK constraints are a second line of defence against a negative balance. Each movement
 * carries a unique reference: repeating it returns the first result and moves nothing.
 */
@Service
public class WalletMovementService {

    private static final Logger log = LoggerFactory.getLogger(WalletMovementService.class);

    /** The outcome, plus whether this call was a repeat of an earlier one. */
    public record Result(MovementResponse movement, boolean replay) {
    }

    private final WalletRepository wallets;
    private final WalletMovementRepository movements;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WalletMovementService(WalletRepository wallets, WalletMovementRepository movements, TransactionTemplate tx, Clock clock) {
        this.wallets = wallets;
        this.movements = movements;
        this.tx = tx;
        this.clock = clock;
    }

    public Result credit(UUID walletId, MovementRequest request) {
        return apply(MovementType.CREDIT, walletId, request);
    }

    public Result debit(UUID walletId, MovementRequest request) {
        return apply(MovementType.DEBIT, walletId, request);
    }

    private Result apply(MovementType type, UUID walletId, MovementRequest request) {
        var earlier = movements.findByReference(request.reference());
        if (earlier.isPresent()) {
            return replay(earlier.get(), type, walletId, request);
        }
        try {
            WalletMovement done = tx.execute(status -> move(type, walletId, request));
            log.info("Wallet {} walletId={} reference={} amount={} {}", type, walletId, request.reference(),
                    request.amount(), request.currency());
            return new Result(MovementResponse.from(done), false);
        } catch (DataIntegrityViolationException e) {
            // An identical request got in first: the unique reference stopped the second one, and its balance change
            // rolled back with it. Answer with the first result.
            return replay(movements.findByReference(request.reference()).orElseThrow(() -> e), type, walletId, request);
        }
    }

    /** One balance change inside the caller's transaction. Also used by {@link WalletTransferService}. */
    WalletMovement move(MovementType type, UUID walletId, MovementRequest request) {
        var now = clock.instant();
        int changed = type == MovementType.DEBIT
                ? wallets.debit(walletId, request.amount(), request.currency(), now)
                : wallets.credit(walletId, request.amount(), request.currency(), now);
        if (changed == 0) {
            throw refusal(type, walletId, request);
        }
        Wallet after = wallets.findById(walletId).orElseThrow(WalletException::notFound);
        return movements.saveAndFlush(new WalletMovement(request.reference(), walletId, type, request.amount(),
                request.currency(), after.getLedgerBalance(), after.getAvailableBalance(), now));
    }

    /** The update changed nothing: work out which rule stopped it, so the answer says why. */
    private WalletException refusal(MovementType type, UUID walletId, MovementRequest request) {
        Wallet wallet = wallets.findById(walletId).orElse(null);
        if (wallet == null) {
            return WalletException.notFound();
        }
        if (!wallet.getCurrency().equals(request.currency())) {
            return WalletException.rule("CURRENCY_MISMATCH",
                    "This wallet holds " + wallet.getCurrency() + ", not " + request.currency());
        }
        if (type == MovementType.DEBIT) {
            if (!wallet.getStatus().allowsOutgoing()) {
                return WalletException.rule("WALLET_NOT_ACTIVE",
                        "This wallet is " + wallet.getStatus() + " and cannot send money");
            }
            return WalletException.rule("INSUFFICIENT_FUNDS", "Not enough available balance");
        }
        return WalletException.rule("WALLET_NOT_ACCEPTING_FUNDS",
                "This wallet is " + wallet.getStatus() + " and cannot receive money");
    }

    /** The same reference must mean the same movement. Anything else is a mistake worth refusing loudly. */
    private Result replay(WalletMovement earlier, MovementType type, UUID walletId, MovementRequest request) {
        boolean same = earlier.getWalletId().equals(walletId) && earlier.getType() == type
                && earlier.getAmount().compareTo(request.amount()) == 0 && earlier.getCurrency().equals(request.currency());
        if (!same) {
            throw WalletException.conflict("REFERENCE_REUSED",
                    "This reference was already used for a different movement");
        }
        return new Result(MovementResponse.from(earlier), true);
    }
}
