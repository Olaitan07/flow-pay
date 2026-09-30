package com.flowpay.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update: a null field means "leave unchanged". An empty string clears an optional address field.
 * {@code email}, {@code phoneNumber} and {@code country} are declared only so an attempt to change them can
 * be rejected with a clear message; they are never applied.
 */
@Schema(description = "Fields to change. Omit a field to leave it unchanged; send an empty string to clear an "
        + "optional address field. Email, phone number and country cannot be changed here.")
public record UpdateCustomerProfileRequest(
        @Schema(description = "Only changeable while the account status is PENDING_VERIFICATION.", example = "Adaeze")
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String firstName,
        @Schema(description = "Only changeable while the account status is PENDING_VERIFICATION.", example = "Obi")
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String lastName,
        @Schema(description = "Address line 1.", example = "12 Marina Road")
        @Size(max = 150) String addressLine1,
        @Schema(description = "Address line 2.", example = "Apt 4B")
        @Size(max = 150) String addressLine2,
        @Schema(description = "City.", example = "Lagos")
        @Size(max = 100) String city,
        @Schema(description = "State or region.", example = "Lagos")
        @Size(max = 100) String state,
        @Schema(description = "Postal code.", example = "101233")
        @Size(max = 20) String postalCode,
        @Schema(description = "NOT changeable here. Sending it returns 422 PROFILE_FIELD_LOCKED.", example = "new@example.com")
        String email,
        @Schema(description = "NOT changeable here. Sending it returns 422 PROFILE_FIELD_LOCKED.", example = "+2348099999999")
        String phoneNumber,
        @Schema(description = "NOT changeable here. Sending it returns 422 PROFILE_FIELD_LOCKED.", example = "GH")
        String country) {
}
