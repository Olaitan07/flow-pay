package com.flowpay.transaction.service;

import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.dto.request.ConfirmFundingRequest;
import com.flowpay.transaction.dto.response.FundingDetailResponse;
import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.entity.FundingStatus;
import com.flowpay.transaction.entity.FundingStatusChange;
import com.flowpay.transaction.entity.ReviewCode;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.repository.FundingRepository;
import com.flowpay.transaction.repository.FundingStatusChangeRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides what happens when the payment provider reports on a funding, and finishes fundings that stalled.
 * <p>
 * The rules that keep customers' money safe:
 * <ul>
 *   <li>The wallet is credited only after the provider confirms the payment, never before.</li>
 *   <li>A FAILED funding never credits the wallet, and can never later become SUCCESS.</li>
 *   <li>The funding reference is the wallet's movement reference, so crediting twice credits once. That makes
 *       "confirmed but the wallet service was down" safe to retry as often as needed.</li>
 *   <li>If money was received but cannot be credited, the funding goes to REQUIRES_REVIEW. It is never dropped.</li>
 *   <li>Every state change is a conditional update plus an audit row in one transaction, so simultaneous or repeated
 *       confirmations cannot double-process a funding.</li>
 * </ul>
 */
@Service
public class FundingProcessor {

    private static final Logger log = LoggerFactory.getLogger(FundingProcessor.class);

    private static final String PROVIDER = "provider";

    private final FundingRepository fundings;
    private final FundingStatusChangeRepository history;
    private final WalletClient walletClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FundingProcessor(FundingRepository fundings, FundingStatusChangeRepository history, WalletClient walletClient,
                            TransactionTemplate tx, Clock clock) {
        this.fundings = fundings;
        this.history = history;
        this.walletClient = walletClient;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FundingDetailResponse detail(String reference) {
        return detailOf(find(reference));
    }

    /** The provider reports that a payment succeeded or failed. */
    public FundingDetailResponse confirm(String reference, ConfirmFundingRequest request) {
        Funding funding = find(reference);
        return request.outcome() == ConfirmFundingRequest.Outcome.FAILED ? fail(funding, request) : succeed(funding, request);
    }

    /** Operations staff retry a funding that is stuck or was refused by the wallet, after the cause is fixed. */
    public FundingDetailResponse retry(String reference, String actor) {
        Funding funding = find(reference);
        if (funding.getStatus() == FundingStatus.PROCESSING) {
            return complete(funding);
        }
        if (funding.getStatus() == FundingStatus.REQUIRES_REVIEW && funding.getReviewCode() == ReviewCode.CREDIT_REFUSED) {
            if (move(funding, FundingStatus.PROCESSING, actor, "Retried after review", null, null, null, null)) {
                return complete(find(reference));
            }
            return detail(reference);
        }
        throw TransactionException.rule("FUNDING_NOT_RETRYABLE",
                "A " + funding.getStatus() + " funding cannot be retried"
                        + (funding.getStatus() == FundingStatus.REQUIRES_REVIEW ? " (" + funding.getReviewCode() + ")" : ""));
    }

    /** Called by the recovery sweep for fundings stuck in PROCESSING. Safe to call any number of times. */
    public void finish(Funding funding) {
        if (funding.getStatus() == FundingStatus.PROCESSING) {
            complete(funding);
        }
    }

    // ------------------------------------------------------------------ the provider says it failed

    private FundingDetailResponse fail(Funding funding, ConfirmFundingRequest request) {
        if (funding.getStatus() == FundingStatus.FAILED) {
            return detailOf(funding);
        }
        if (funding.getStatus() != FundingStatus.PENDING) {
            throw TransactionException.conflict("FUNDING_STATE_CONFLICT",
                    "This funding is already " + funding.getStatus() + " and cannot be marked FAILED");
        }
        String reason = request.failureReason() == null || request.failureReason().isBlank() ? "Payment failed" : request.failureReason().trim();
        if (move(funding, FundingStatus.FAILED, PROVIDER, reason, request.providerReference(), reason, null, null)) {
            log.info("Funding failed reference={} reason={}", funding.getReference(), reason);
            return detail(funding.getReference());
        }
        // Someone else moved it first. A repeat of the same news is fine; anything else is a real conflict.
        Funding now = find(funding.getReference());
        if (now.getStatus() == FundingStatus.FAILED) {
            return detailOf(now);
        }
        throw TransactionException.conflict("FUNDING_STATE_CONFLICT",
                "This funding is already " + now.getStatus() + " and cannot be marked FAILED");
    }

    // ------------------------------------------------------------------ the provider says it succeeded

    private FundingDetailResponse succeed(Funding funding, ConfirmFundingRequest request) {
        if (request.amount() == null || request.currency() == null) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "amount and currency are required when the outcome is SUCCESS");
        }
        switch (funding.getStatus()) {
            case FAILED -> throw TransactionException.conflict("FUNDING_ALREADY_FAILED",
                    "This funding was already marked FAILED and cannot become SUCCESS");
            case SUCCESS, REQUIRES_REVIEW -> {
                return detailOf(funding);
            }
            case PROCESSING -> {
                return complete(funding);
            }
            case PENDING -> {
                return confirmPending(funding, request);
            }
        }
        throw new IllegalStateException("Unhandled funding status " + funding.getStatus());
    }

    private FundingDetailResponse confirmPending(Funding funding, ConfirmFundingRequest request) {
        boolean matches = request.amount().compareTo(funding.getAmount()) == 0 && request.currency().equals(funding.getCurrency());
        if (!matches) {
            // Never credit an amount nobody asked for. Hold it for a person to decide.
            String reason = "Provider reported " + request.amount().toPlainString() + " " + request.currency()
                    + ", expected " + funding.getAmount().toPlainString() + " " + funding.getCurrency();
            if (move(funding, FundingStatus.REQUIRES_REVIEW, PROVIDER, reason, request.providerReference(), null,
                    ReviewCode.AMOUNT_MISMATCH, reason)) {
                log.warn("Funding amount mismatch reference={} {}", funding.getReference(), reason);
            }
            return detail(funding.getReference());
        }
        if (move(funding, FundingStatus.PROCESSING, PROVIDER, "Payment confirmed", request.providerReference(), null, null, null)) {
            return complete(find(funding.getReference()));
        }
        // Lost the race to another confirmation: act on whatever state it left behind.
        Funding now = find(funding.getReference());
        return now.getStatus() == FundingStatus.PROCESSING ? complete(now) : succeed(now, request);
    }

    /**
     * Payment is confirmed: put the money in the wallet. The wallet answers one of three ways. Credited: done.
     * Refused: a person must decide (the money was received). Unavailable: leave it PROCESSING and try again later.
     */
    private FundingDetailResponse complete(Funding funding) {
        var outcome = walletClient.credit(funding.getWalletId(), funding.getReference(), funding.getAmount(), funding.getCurrency());
        switch (outcome.kind()) {
            case CREDITED -> {
                if (move(funding, FundingStatus.SUCCESS, "system", "Wallet credited", null, null, null, null)) {
                    log.info("Funding succeeded reference={} walletId={} amount={} {}", funding.getReference(),
                            funding.getWalletId(), funding.getAmount(), funding.getCurrency());
                }
            }
            case REFUSED -> {
                String reason = "The wallet refused the money: " + outcome.code();
                if (move(funding, FundingStatus.REQUIRES_REVIEW, "system", reason, null, null, ReviewCode.CREDIT_REFUSED, reason)) {
                    log.error("Funding needs review reference={} {}", funding.getReference(), reason);
                }
            }
            case UNAVAILABLE -> log.warn("Wallet could not be credited yet for funding {}; it will be retried", funding.getReference());
        }
        return detail(funding.getReference());
    }

    // ------------------------------------------------------------------ helpers

    /** One legal state change: the conditional update and its audit row, together or not at all. */
    private boolean move(Funding funding, FundingStatus next, String actor, String detail, String providerReference,
                         String failureReason, ReviewCode reviewCode, String reviewReason) {
        FundingStatus from = funding.getStatus();
        if (!from.canMoveTo(next)) {
            throw new IllegalStateException("Illegal funding move " + from + " -> " + next);
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            var now = clock.instant();
            int changed = fundings.transition(funding.getId(), from, next, now, providerReference, failureReason, reviewCode, reviewReason);
            if (changed == 1) {
                history.save(new FundingStatusChange(funding.getId(), from, next, actor, detail, now));
            }
            return changed == 1;
        }));
    }

    private Funding find(String reference) {
        return fundings.findByReference(reference).orElseThrow(TransactionException::fundingNotFound);
    }

    private FundingDetailResponse detailOf(Funding funding) {
        return FundingDetailResponse.of(funding, history.findByFundingIdOrderByCreatedAtAsc(funding.getId()));
    }
}
