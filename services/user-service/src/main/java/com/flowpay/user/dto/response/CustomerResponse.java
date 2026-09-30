package com.flowpay.user.dto.response;

import com.flowpay.user.entity.Customer;
import com.flowpay.user.entity.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The account that was just created.")
public record CustomerResponse(
        @Schema(description = "Unique customer id. Use it in /api/v1/users/{id}.",
                example = "6fbf8203-6ee0-4b39-aa85-d721d712480c") UUID id,
        @Schema(example = "Ada") String firstName,
        @Schema(example = "Obi") String lastName,
        @Schema(description = "Always lowercase.", example = "ada.obi@example.com") String email,
        @Schema(example = "+2348012345678") String phoneNumber,
        @Schema(example = "NG") String country,
        @Schema(description = "New accounts start as PENDING_VERIFICATION.", example = "PENDING_VERIFICATION")
        CustomerStatus status,
        @Schema(example = "2026-09-30T16:13:30.942Z") Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getFirstName(), customer.getLastName(),
                customer.getEmail(), customer.getPhoneNumber(), customer.getCountry(),
                customer.getStatus(), customer.getCreatedAt());
    }
}
