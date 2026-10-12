package com.flowpay.transaction.service;

import com.flowpay.transaction.configuration.TransactionProperties;
import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.repository.FundingRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes fundings whose payment was confirmed but whose wallet credit never completed, for example because the
 * wallet service was down at that moment. Without this, a customer who has paid could wait forever.
 * Retrying is safe: the wallet service credits a given reference only once.
 */
@Component
public class FundingRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(FundingRecoveryJob.class);

    private final FundingRepository fundings;
    private final FundingProcessor processor;
    private final TransactionProperties properties;
    private final Clock clock;

    public FundingRecoveryJob(FundingRepository fundings, FundingProcessor processor, TransactionProperties properties, Clock clock) {
        this.fundings = fundings;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowpay.transaction.recovery.interval:30s}")
    public void sweep() {
        run();
    }

    /** One pass. Returns how many fundings were attempted. */
    public int run() {
        var cutoff = clock.instant().minus(properties.recovery().minAge());
        List<Funding> stuck = fundings.findStuckProcessing(cutoff, PageRequest.of(0, properties.recovery().batchSize()));
        for (Funding funding : stuck) {
            try {
                processor.finish(funding);
            } catch (RuntimeException e) {
                // One bad funding must not stop the rest from being finished.
                log.error("Recovery failed for funding {}", funding.getReference(), e);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("Funding recovery attempted {} stuck fundings", stuck.size());
        }
        return stuck.size();
    }
}
