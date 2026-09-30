package com.flowpay.user.dto.response;

import com.flowpay.user.entity.Customer;
import com.flowpay.user.entity.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A customer's profile. Never includes any password data.")
public record CustomerProfileResponse(
        @Schema(example = "6fbf8203-6ee0-4b39-aa85-d721d712480c") UUID id,
        @Schema(example = "Ada") String firstName,
        @Schema(example = "Obi") String lastName,
        @Schema(example = "ada.obi@example.com") String email,
        @Schema(example = "+2348012345678") String phoneNumber,
        @Schema(example = "NG") String country,
        @Schema(description = "Null until set.", example = "12 Marina Road") String addressLine1,
        @Schema(description = "Null until set.", example = "Apt 4B") String addressLine2,
        @Schema(description = "Null until set.", example = "Lagos") String city,
        @Schema(description = "Null until set.", example = "Lagos") String state,
        @Schema(description = "Null until set.", example = "101233") String postalCode,
        @Schema(description = "PENDING_VERIFICATION, ACTIVE, SUSPENDED, BLOCKED or CLOSED.",
                example = "PENDING_VERIFICATION") CustomerStatus status,
        @Schema(example = "2026-09-30T16:13:30.942Z") Instant createdAt,
        @Schema(example = "2026-09-30T16:20:00.000Z") Instant updatedAt) {

    public static CustomerProfileResponse from(Customer c) {
        return new CustomerProfileResponse(c.getId(), c.getFirstName(), c.getLastName(), c.getEmail(),
                c.getPhoneNumber(), c.getCountry(), c.getAddressLine1(), c.getAddressLine2(), c.getCity(),
                c.getState(), c.getPostalCode(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
