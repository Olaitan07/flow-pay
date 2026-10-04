package com.flowpay.kyc.dto.request;

import com.flowpay.kyc.entity.IdType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Tier 2 verification: a government-issued ID. Requires tier 1 to be verified first.")
public record SubmitTierTwoRequest(
        @Schema(description = "Kind of ID. NIN and BVN must be exactly 11 digits.", example = "NIN")
        @NotNull IdType idType,
        @Schema(description = "The ID number. Spaces and dashes are ignored. Stored encrypted and shown masked.",
                example = "12345678901")
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9 -]{6,25}$", message = "must be 6-25 letters, digits, spaces or dashes")
        String idNumber) {

    /** The ID number is highly sensitive and must never reach logs. */
    @Override
    public String toString() {
        return "SubmitTierTwoRequest[idType=" + idType + "]";
    }
}
