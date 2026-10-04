package com.flowpay.kyc.outbox;

import com.flowpay.kyc.configuration.KycConfiguration;
import com.flowpay.kyc.entity.OutboxEvent;
import com.flowpay.kyc.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds an event to the outbox table. Call it inside the same transaction as the change it describes.
 * Events carry identifiers and the new level only, never personal data.
 */
@Component
public class KycEventRecorder {

    public record KycLevelChanged(String eventId, String type, String customerId, int level, String occurredAt) {
    }

    private final OutboxEventRepository outbox;
    private final JsonMapper json = JsonMapper.builder().build();

    public KycEventRecorder(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    public void levelChanged(UUID customerId, int level, Instant now) {
        UUID eventId = UUID.randomUUID();
        String payload = json.writeValueAsString(new KycLevelChanged(
                eventId.toString(), "KYC_LEVEL_CHANGED", customerId.toString(), level, now.toString()));
        outbox.save(new OutboxEvent(eventId, KycConfiguration.EVENTS_TOPIC, customerId.toString(), payload, now));
    }
}
