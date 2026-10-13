package com.flowpay.transaction.service;

import com.flowpay.transaction.client.KycClient;
import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.dto.request.InitiateTransferRequest;
import com.flowpay.transaction.dto.request.QuoteTransferRequest;
import com.flowpay.transaction.dto.response.QuoteResponse;
import com.flowpay.transaction.dto.response.TransferResponse;
import com.flowpay.transaction.entity.Transfer;
import com.flowpay.transaction.entity.TransferStatus;
import com.flowpay.transaction.entity.TransferStatusChange;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.repository.TransferRepository;
import com.flowpay.transaction.repository.TransferStatusChangeRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starting a transfer and reading it back. Before any money moves it checks, in order: the request itself, whether this
 * is a retry, the sender's verification level, both wallets, and the fee. Only then is the transfer recorded as
 * PROCESSING and handed to {@link TransferProcessor}. The wallet service repeats the checks that matter for money
 * (status, currency, balance) atomically, so the checks here give clear early answers but are never the only guard.
 * <p>
 * Not here yet, by design: daily/weekly/monthly limits (Epic 12) and risk screening (Epic 22). The KYC check already
 * enforces the per-transaction cap of the sender's level.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /** What happened, so the controller can pick the right HTTP status. */
    public enum Result { CREATED, REPLAYED, IN_PROGRESS }

    public record Outcome(TransferResponse transfer, Result result) {
    }

    private final TransferRepository transfers;
    private final TransferStatusChangeRepository history;
    private final TransferProcessor processor;
    private final TransferFeeCalculator feeCalculator;
    private final WalletClient walletClient;
    private final KycClient kycClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferService(TransferRepository transfers, TransferStatusChangeRepository history, TransferProcessor processor,
                           TransferFeeCalculator feeCalculator, WalletClient walletClient, KycClient kycClient,
                           TransactionTemplate tx, Clock clock) {
        this.transfers = transfers;
        this.history = history;
        this.processor = processor;
        this.feeCalculator = feeCalculator;
        this.walletClient = walletClient;
        this.kycClient = kycClient;
        this.tx = tx;
        this.clock = clock;
    }

    /** What a transfer would cost. Moves and records nothing. */
    public QuoteResponse quote(QuoteTransferRequest request) {
        BigDecimal fee = feeCalculator.feeFor(request.amount(), request.currency());
        return new QuoteResponse(request.amount(), fee, request.amount().add(fee), request.currency());
    }

    public Outcome initiate(UUID senderId, String idempotencyKey, InitiateTransferRequest request) {
        if (senderId.equals(request.recipientCustomerId())) {
            throw TransactionException.rule("SELF_TRANSFER", "You cannot send money to yourself");
        }
        String narration = request.narration() == null || request.narration().isBlank() ? null : request.narration().trim();
        String hash = fingerprint(request, narration);

        // A retry of something already recorded is answered from the record, before anything else is checked or charged.
        var earlier = transfers.findBySenderCustomerIdAndIdempotencyKey(senderId, idempotencyKey);
        if (earlier.isPresent()) {
            return answer(earlier.get(), hash, senderId);
        }

        var decision = kycClient.mayTransfer(senderId, request.amount(), request.currency());
        if (!decision.allowed()) {
            throw kycRefusal(decision);
        }

        var senderWallet = walletClient.findByCustomer(senderId, request.currency())
                .orElseThrow(() -> new TransactionException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                        "You do not have a " + request.currency() + " wallet"));
        if (!"ACTIVE".equals(senderWallet.status())) {
            throw TransactionException.rule("WALLET_NOT_ACTIVE", "Your wallet is " + senderWallet.status() + " and cannot send money");
        }
        var recipientWallet = walletClient.findByCustomer(request.recipientCustomerId(), request.currency())
                .orElseThrow(() -> new TransactionException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND",
                        "We could not find a " + request.currency() + " wallet for this recipient"));
        if (!"ACTIVE".equals(recipientWallet.status()) && !"FROZEN".equals(recipientWallet.status())) {
            throw TransactionException.rule("RECIPIENT_NOT_ACCEPTING_FUNDS", "The recipient cannot receive money right now");
        }

        BigDecimal fee = feeCalculator.feeFor(request.amount(), request.currency());
        var now = clock.instant();
        Transfer transfer = new Transfer(newReference(), senderId, request.recipientCustomerId(), senderWallet.id(),
                recipientWallet.id(), request.amount(), fee, request.currency(), narration, idempotencyKey, hash, now);
        try {
            tx.executeWithoutResult(status -> {
                transfers.saveAndFlush(transfer);
                history.save(new TransferStatusChange(transfer.getId(), null, TransferStatus.PROCESSING, "customer",
                        "Transfer requested", now));
            });
        } catch (DataIntegrityViolationException e) {
            // The same customer sent the same key twice at the same moment; the unique key let exactly one through.
            var winner = transfers.findBySenderCustomerIdAndIdempotencyKey(senderId, idempotencyKey).orElseThrow(() -> e);
            return answer(winner, hash, senderId);
        }
        log.info("Transfer requested reference={} senderId={} recipientId={} amount={} fee={} {}", transfer.getReference(),
                senderId, request.recipientCustomerId(), request.amount(), fee, request.currency());
        return respond(processor.settle(transfer), Result.CREATED, senderId);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID customerId, String reference) {
        return transfers.findVisible(reference, customerId).map(t -> TransferResponse.of(t, customerId))
                .orElseThrow(TransactionException::transferNotFound);
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> list(UUID customerId, int limit) {
        return transfers.findVisibleTo(customerId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(t -> TransferResponse.of(t, customerId)).toList();
    }

    // ------------------------------------------------------------------ retries

    /** The customer sent a key we have seen. Same request: give the original answer. Different request: refuse. */
    private Outcome answer(Transfer existing, String hash, UUID senderId) {
        if (!existing.getRequestHash().equals(hash)) {
            throw TransactionException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different transfer. Use a new key for a new transfer");
        }
        // Still PROCESSING means the first attempt could not be confirmed: try to finish it now (safe to repeat).
        return respond(processor.settle(existing), Result.REPLAYED, senderId);
    }

    private Outcome respond(Transfer transfer, Result how, UUID senderId) {
        return switch (transfer.getStatus()) {
            case SUCCESS -> new Outcome(TransferResponse.of(transfer, senderId), how);
            case PROCESSING -> new Outcome(TransferResponse.of(transfer, senderId), Result.IN_PROGRESS);
            case FAILED -> throw TransactionException.rule(transfer.getFailureCode(), transfer.getFailureReason());
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Identifies what was asked for, so a reused key with different details can be told apart from a true retry. */
    static String fingerprint(InitiateTransferRequest request, String narration) {
        String canonical = request.recipientCustomerId() + "|" + request.amount().setScale(2).toPlainString() + "|"
                + request.currency() + "|" + (narration == null ? "" : narration);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TransactionException kycRefusal(KycClient.Decision decision) {
        String code = decision.reasonCode() == null ? "KYC_NOT_PERMITTED" : decision.reasonCode();
        String message = switch (code) {
            case "KYC_LEVEL_TOO_LOW" -> "Verify your identity (level " + decision.requiredLevel() + ") before sending money";
            case "KYC_LIMIT_EXCEEDED" -> "This amount is above what your verification level allows. Verify further to raise your limit";
            case "UNSUPPORTED_CURRENCY" -> "This currency is not supported";
            default -> "Your verification level does not allow this";
        };
        // An unsupported currency is a problem with the request, not a restriction on the customer.
        return new TransactionException(code.equals("UNSUPPORTED_CURRENCY") ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.FORBIDDEN,
                code, message);
    }

    /** A hard-to-guess, unique, printable reference such as TRF-9F3A91C2B7D04E1A8C55. */
    static String newReference() {
        return "TRF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
