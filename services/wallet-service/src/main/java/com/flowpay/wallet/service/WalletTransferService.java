package com.flowpay.wallet.service;

import com.flowpay.wallet.configuration.WalletProperties;
import com.flowpay.wallet.dto.request.MovementRequest;
import com.flowpay.wallet.dto.request.WalletTransferRequest;
import com.flowpay.wallet.dto.response.MovementResponse;
import com.flowpay.wallet.dto.response.WalletTransferResponse;
import com.flowpay.wallet.entity.MovementType;
import com.flowpay.wallet.entity.WalletMovement;
import com.flowpay.wallet.entity.WalletStatus;
import com.flowpay.wallet.exception.WalletException;
import com.flowpay.wallet.repository.WalletMovementRepository;
import com.flowpay.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves money between two wallets, and the fee to the platform, as one all-or-nothing database transaction.
 * <p>
 * Both wallets live in this service's database, so a single transaction is enough: money can never leave the sender
 * without arriving, and a half-finished transfer cannot exist. Each leg is the same one-statement conditional update
 * a plain debit or credit uses, so overspending is impossible however many transfers run at once.
 * <ul>
 *   <li>Legs run in wallet-id order, so two opposite transfers (A to B and B to A) can never wait on each other.</li>
 *   <li>The reference makes a repeat safe: the same request returns the first result and moves nothing.</li>
 *   <li>Money is conserved: sender pays amount + fee, recipient gets amount, the platform gets fee.</li>
 * </ul>
 */
@Service
public class WalletTransferService {

    private static final Logger log = LoggerFactory.getLogger(WalletTransferService.class);

    private enum Role { SENDER, RECIPIENT, FEE }

    private record Leg(Role role, UUID walletId, MovementType type, BigDecimal amount, String reference) {
    }

    /** The outcome, plus whether this call was a repeat of an earlier one. */
    public record Result(WalletTransferResponse transfer, boolean replay) {
    }

    private final WalletRepository wallets;
    private final WalletMovementRepository movements;
    private final WalletMovementService movementService;
    private final WalletProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WalletTransferService(WalletRepository wallets, WalletMovementRepository movements,
                                 WalletMovementService movementService, WalletProperties properties,
                                 TransactionTemplate tx, Clock clock) {
        this.wallets = wallets;
        this.movements = movements;
        this.movementService = movementService;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    public Result transfer(WalletTransferRequest request) {
        if (request.senderWalletId().equals(request.recipientWalletId())) {
            throw WalletException.rule("SAME_WALLET", "Sender and recipient wallets must be different");
        }
        var earlier = movements.findByReference(request.reference() + ":DEBIT");
        if (earlier.isPresent()) {
            return replay(request, earlier.get());
        }
        UUID feeWalletId = request.fee().signum() > 0 ? feeWallet(request.currency()) : null;
        try {
            WalletTransferResponse done = tx.execute(status -> execute(request, feeWalletId));
            log.info("Transfer done reference={} sender={} recipient={} amount={} fee={} {}", request.reference(),
                    request.senderWalletId(), request.recipientWalletId(), request.amount(), request.fee(), request.currency());
            return new Result(done, false);
        } catch (DataIntegrityViolationException | WalletException e) {
            // An identical request may have got in first. Then this one was stopped (by the unique reference, or by
            // finding the money already spent) and its changes rolled back: answer with the first result.
            var first = movements.findByReference(request.reference() + ":DEBIT");
            if (first.isPresent()) {
                return replay(request, first.get());
            }
            throw e;
        }
    }

    private WalletTransferResponse execute(WalletTransferRequest r, UUID feeWalletId) {
        BigDecimal total = r.amount().add(r.fee());
        var legs = new ArrayList<Leg>();
        legs.add(new Leg(Role.SENDER, r.senderWalletId(), MovementType.DEBIT, total, r.reference() + ":DEBIT"));
        legs.add(new Leg(Role.RECIPIENT, r.recipientWalletId(), MovementType.CREDIT, r.amount(), r.reference() + ":CREDIT"));
        if (feeWalletId != null) {
            legs.add(new Leg(Role.FEE, feeWalletId, MovementType.CREDIT, r.fee(), r.reference() + ":FEE"));
        }
        legs.sort(Comparator.comparing(Leg::walletId));

        Map<Role, WalletMovement> done = new EnumMap<>(Role.class);
        for (Leg leg : legs) {
            try {
                done.put(leg.role(), movementService.move(leg.type(), leg.walletId(),
                        new MovementRequest(leg.reference(), leg.amount(), r.currency())));
            } catch (WalletException e) {
                throw explain(leg.role(), e);
            }
        }
        return respond(r.reference(), done.get(Role.SENDER), done.get(Role.RECIPIENT), done.get(Role.FEE));
    }

    /** The same refusal, but saying whose wallet it was, because the caller must tell the customer apart from a fault of ours. */
    private static WalletException explain(Role role, WalletException e) {
        return switch (role) {
            case SENDER -> e.getErrorCode().equals("WALLET_NOT_FOUND")
                    ? new WalletException(HttpStatus.NOT_FOUND, "SENDER_WALLET_NOT_FOUND", "The sender's wallet was not found") : e;
            case RECIPIENT -> switch (e.getErrorCode()) {
                case "WALLET_NOT_FOUND" -> new WalletException(HttpStatus.NOT_FOUND, "RECIPIENT_WALLET_NOT_FOUND",
                        "The recipient's wallet was not found");
                case "WALLET_NOT_ACCEPTING_FUNDS" -> WalletException.rule("RECIPIENT_NOT_ACCEPTING_FUNDS",
                        "The recipient's wallet cannot receive money right now");
                default -> e;
            };
            // A problem with our own fee wallet is not the customer's fault and not a final answer: report it as
            // "try again" so the transfer is not marked failed and is retried once the fee wallet is fixed.
            case FEE -> new WalletException(HttpStatus.SERVICE_UNAVAILABLE, "FEE_WALLET_UNAVAILABLE",
                    "The fee cannot be collected right now");
        };
    }

    private UUID feeWallet(String currency) {
        UUID owner = properties.feeCustomerId();
        tx.executeWithoutResult(status -> wallets.createIfAbsent(UUID.randomUUID(), owner, currency, clock.instant()));
        return wallets.findFirstByCustomerIdAndCurrencyAndStatusNot(owner, currency, WalletStatus.CLOSED)
                .orElseThrow(() -> new WalletException(HttpStatus.SERVICE_UNAVAILABLE, "FEE_WALLET_UNAVAILABLE",
                        "The fee cannot be collected right now"))
                .getId();
    }

    /** The same reference must mean the same transfer. Anything else is a mistake worth refusing loudly. */
    private Result replay(WalletTransferRequest r, WalletMovement debit) {
        var credit = movements.findByReference(r.reference() + ":CREDIT").orElse(null);
        var fee = movements.findByReference(r.reference() + ":FEE").orElse(null);
        boolean same = debit.getWalletId().equals(r.senderWalletId())
                && debit.getAmount().compareTo(r.amount().add(r.fee())) == 0
                && debit.getCurrency().equals(r.currency())
                && credit != null && credit.getWalletId().equals(r.recipientWalletId())
                && credit.getAmount().compareTo(r.amount()) == 0
                && (r.fee().signum() == 0 ? fee == null : fee != null && fee.getAmount().compareTo(r.fee()) == 0);
        if (!same) {
            throw WalletException.conflict("REFERENCE_REUSED", "This reference was already used for a different transfer");
        }
        return new Result(respond(r.reference(), debit, credit, fee), true);
    }

    private static WalletTransferResponse respond(String reference, WalletMovement debit, WalletMovement credit, WalletMovement fee) {
        return new WalletTransferResponse(reference, MovementResponse.from(debit), MovementResponse.from(credit),
                fee == null ? null : MovementResponse.from(fee));
    }
}
