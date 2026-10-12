package com.flowpay.kyc.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Tier 1 verification: date of birth and residential address.")
public record SubmitTierOneRequest(
        @Schema(description = "Date of birth (yyyy-MM-dd). You must be at least 18.", example = "1994-05-17")
        @NotNull @Past LocalDate dateOfBirth,
        @Schema(example = "12 Marina Road") @NotBlank @Size(max = 150) String addressLine1,
        @Schema(example = "Apt 4B") @Size(max = 150) String addressLine2,
        @Schema(example = "Lagos") @NotBlank @Size(max = 100) String city,
        @Schema(example = "Lagos") @NotBlank @Size(max = 100) String state,
        @Schema(example = "101233") @NotBlank @Size(max = 20) String postalCode,
        @Schema(description = "Country of residence, ISO 3166-1 alpha-2.", example = "NG")
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code, e.g. NG") String country) {

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "SubmitTierOneRequest[]";
    }
}
