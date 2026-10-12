package com.flowpay.kyc.outbox;

import com.flowpay.kyc.entity.OutboxEvent;
import com.flowpay.kyc.repository.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends saved events to Kafka. If Kafka is down the events simply wait in the table and go out later, so a
 * decision is never lost. Delivery is "at least once": a crash between sending and marking can send an event
 * twice, which is why consumers must tolerate duplicates.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                           TransactionTemplate transaction, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowpay.kyc.outbox.poll-interval:2s}")
    public void publishPending() {
        try {
            transaction.executeWithoutResult(status -> {
                List<OutboxEvent> batch = outbox.lockUnpublished(BATCH_SIZE);
                for (OutboxEvent event : batch) {
                    try {
                        kafka.send(event.getTopic(), event.getEventKey(), event.getPayload()).get(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        // Stop here: later events must not overtake this one. Already-sent ones are still marked.
                        log.warn("Outbox publish failed, will retry eventId={} cause={}", event.getId(),
                                ex.getClass().getSimpleName());
                        return;
                    }
                    event.markPublished(clock.instant());
                }
            });
        } catch (RuntimeException ex) {
            log.error("Outbox polling failed cause={}", ex.getClass().getSimpleName());
        }
    }
}
