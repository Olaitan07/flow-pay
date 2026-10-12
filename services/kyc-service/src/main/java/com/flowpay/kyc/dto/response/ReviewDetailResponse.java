package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.IdType;
import com.flowpay.kyc.entity.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Everything a reviewer needs, including the full ID number. Viewing it is recorded in the audit trail.")
public record ReviewDetailResponse(
        UUID id,
        UUID customerId,
        int tier,
        KycStatus status,
        Instant submittedAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        LocalDate dateOfBirth,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String addressCountry,
        IdType idType,
        @Schema(description = "The full ID number (decrypted). Handle with care.", example = "12345678901")
        String idNumber,
        @Schema(description = "Everything that happened to this submission, oldest first.")
        List<HistoryEntry> history) {

    @Schema(description = "One line of the audit trail.")
    public record HistoryEntry(
            @Schema(example = "SUBMITTED") String type,
            @Schema(description = "The customer id for submissions, or the reviewer's name.", example = "reviewer.jane") String actor,
            String reason,
            Instant at) {
    }

    /** The ID number is highly sensitive; keep it out of logs. */
    @Override
    public String toString() {
        return "ReviewDetailResponse[id=" + id + "]";
    }
}
