package com.flowpay.wallet.service;

import com.flowpay.wallet.client.KycClient;
import com.flowpay.wallet.configuration.WalletProperties;
import com.flowpay.wallet.dto.response.WalletResponse;
import com.flowpay.wallet.entity.Wallet;
import com.flowpay.wallet.entity.WalletStatus;
import com.flowpay.wallet.exception.WalletException;
import com.flowpay.wallet.repository.WalletRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening wallets and reading them. A customer only ever sees their own. */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final KycClient kycClient;
    private final WalletProperties properties;
    private final Clock clock;

    public WalletService(WalletRepository wallets, KycClient kycClient, WalletProperties properties, Clock clock) {
        this.wallets = wallets;
        this.kycClient = kycClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens an ACTIVE wallet with zero balance. Refused if the currency is not supported, if the customer is not
     * verified enough, or if they already have a live wallet in that currency.
     */
    public WalletResponse create(UUID customerId, String currency) {
        if (!properties.supportedCurrencies().contains(currency)) {
            throw WalletException.rule("UNSUPPORTED_CURRENCY",
                    "Currency " + currency + " is not supported. Supported: " + String.join(", ", properties.supportedCurrencies()));
        }
        int level = kycClient.levelOf(customerId);
        if (level < properties.kyc().requiredLevel()) {
            throw new WalletException(HttpStatus.FORBIDDEN, "KYC_LEVEL_TOO_LOW",
                    "Verify your identity (level " + properties.kyc().requiredLevel() + ") before opening a wallet");
        }
        if (wallets.existsLive(customerId, currency)) {
            throw alreadyExists(currency);
        }
        try {
            Wallet saved = wallets.saveAndFlush(new Wallet(customerId, currency, clock.instant()));
            log.info("Wallet opened walletId={} customerId={} currency={}", saved.getId(), customerId, currency);
            return WalletResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Two requests raced past the check above; the database's unique index let exactly one through.
            throw alreadyExists(currency);
        }
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> listFor(UUID customerId) {
        return wallets.findByCustomerIdOrderByCreatedAtAsc(customerId).stream().map(WalletResponse::from).toList();
    }

    /** Someone else's wallet looks exactly like a wallet that does not exist. */
    @Transactional(readOnly = true)
    public WalletResponse getFor(UUID customerId, UUID walletId) {
        return wallets.findByIdAndCustomerId(walletId, customerId).map(WalletResponse::from)
                .orElseThrow(WalletException::notFound);
    }

    /** For other FlowPay services: the customer's live wallet in a currency. Not exposed to customers. */
    @Transactional(readOnly = true)
    public WalletResponse liveWalletOf(UUID customerId, String currency) {
        return wallets.findFirstByCustomerIdAndCurrencyAndStatusNot(customerId, currency, WalletStatus.CLOSED)
                .map(WalletResponse::from).orElseThrow(WalletException::notFound);
    }

    private static WalletException alreadyExists(String currency) {
        return WalletException.conflict("WALLET_ALREADY_EXISTS", "You already have a " + currency + " wallet");
    }
}
