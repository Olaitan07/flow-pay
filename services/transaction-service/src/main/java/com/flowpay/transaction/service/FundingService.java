package com.flowpay.transaction.service;

import com.flowpay.transaction.client.KycClient;
import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.dto.request.InitiateFundingRequest;
import com.flowpay.transaction.dto.response.FundingResponse;
import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.entity.FundingStatus;
import com.flowpay.transaction.entity.FundingStatusChange;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.repository.FundingRepository;
import com.flowpay.transaction.repository.FundingStatusChangeRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starting a funding and reading it back. Starting one moves no money: it records the request as PENDING after
 * checking that the wallet is the customer's and can take the money, and that their verification level allows it.
 */
@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    /** Wallet statuses that cannot receive money, so there is no point taking a payment for them. */
    private static final Set<String> CLOSED_TO_FUNDS = Set.of("BLOCKED", "CLOSED");

    private final FundingRepository fundings;
    private final FundingStatusChangeRepository history;
    private final WalletClient walletClient;
    private final KycClient kycClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FundingService(FundingRepository fundings, FundingStatusChangeRepository history, WalletClient walletClient,
                          KycClient kycClient, TransactionTemplate tx, Clock clock) {
        this.fundings = fundings;
        this.history = history;
        this.walletClient = walletClient;
        this.kycClient = kycClient;
        this.tx = tx;
        this.clock = clock;
    }

    public FundingResponse initiate(UUID customerId, InitiateFundingRequest request) {
        // Someone else's wallet looks exactly like a wallet that does not exist.
        var wallet = walletClient.find(request.walletId()).filter(w -> customerId.equals(w.customerId()))
                .orElseThrow(() -> new TransactionException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
        if (!wallet.currency().equals(request.currency())) {
            throw TransactionException.rule("CURRENCY_MISMATCH",
                    "This wallet holds " + wallet.currency() + ", not " + request.currency());
        }
        if (CLOSED_TO_FUNDS.contains(wallet.status())) {
            throw TransactionException.rule("WALLET_NOT_ACCEPTING_FUNDS", "This wallet is " + wallet.status() + " and cannot receive money");
        }
        var decision = kycClient.mayFundWallet(customerId, request.amount(), request.currency());
        if (!decision.allowed()) {
            throw kycRefusal(decision);
        }

        var now = clock.instant();
        Funding funding = new Funding(newReference(), customerId, request.walletId(), request.source(), request.amount(),
                request.currency(), now);
        tx.executeWithoutResult(status -> {
            fundings.save(funding);
            history.save(new FundingStatusChange(funding.getId(), null, FundingStatus.PENDING, "customer", "Funding requested", now));
        });
        log.info("Funding requested reference={} customerId={} walletId={} amount={} {} source={}", funding.getReference(),
                customerId, funding.getWalletId(), funding.getAmount(), funding.getCurrency(), funding.getSource());
        return FundingResponse.from(funding);
    }

    @Transactional(readOnly = true)
    public FundingResponse get(UUID customerId, String reference) {
        return fundings.findByReferenceAndCustomerId(reference, customerId).map(FundingResponse::from)
                .orElseThrow(TransactionException::fundingNotFound);
    }

    @Transactional(readOnly = true)
    public List<FundingResponse> list(UUID customerId, int limit) {
        return fundings.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(FundingResponse::from).toList();
    }

    private static TransactionException kycRefusal(KycClient.Decision decision) {
        String code = decision.reasonCode() == null ? "KYC_NOT_PERMITTED" : decision.reasonCode();
        String message = switch (code) {
            case "KYC_LEVEL_TOO_LOW" -> "Verify your identity (level " + decision.requiredLevel() + ") before funding your wallet";
            case "KYC_LIMIT_EXCEEDED" -> "This amount is above what your verification level allows. Verify further to raise your limit";
            default -> "Your verification level does not allow this";
        };
        return new TransactionException(HttpStatus.FORBIDDEN, code, message);
    }

    /** A hard-to-guess, unique, printable reference such as FND-9F3A91C2B7D04E1A8C55. */
    static String newReference() {
        return "FND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
