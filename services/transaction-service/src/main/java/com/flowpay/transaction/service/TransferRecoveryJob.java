package com.flowpay.transaction.service;

import com.flowpay.transaction.configuration.TransactionProperties;
import com.flowpay.transaction.entity.Transfer;
import com.flowpay.transaction.repository.TransferRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes transfers that were recorded but never confirmed, for example because the wallet service was down at that
 * moment. Without this, such a transfer would stay PROCESSING forever.
 * Retrying is safe: the wallet service moves a given reference only once.
 */
@Component
public class TransferRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryJob.class);

    private final TransferRepository transfers;
    private final TransferProcessor processor;
    private final TransactionProperties properties;
    private final Clock clock;

    public TransferRecoveryJob(TransferRepository transfers, TransferProcessor processor, TransactionProperties properties, Clock clock) {
        this.transfers = transfers;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowpay.transaction.recovery.interval:30s}")
    public void sweep() {
        run();
    }

    /** One pass. Returns how many transfers were attempted. */
    public int run() {
        var cutoff = clock.instant().minus(properties.recovery().minAge());
        List<Transfer> stuck = transfers.findStuckProcessing(cutoff, PageRequest.of(0, properties.recovery().batchSize()));
        for (Transfer transfer : stuck) {
            try {
                processor.settle(transfer);
            } catch (RuntimeException e) {
                // One bad transfer must not stop the rest from being finished.
                log.error("Recovery failed for transfer {}", transfer.getReference(), e);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("Transfer recovery attempted {} stuck transfers", stuck.size());
        }
        return stuck.size();
    }
}
