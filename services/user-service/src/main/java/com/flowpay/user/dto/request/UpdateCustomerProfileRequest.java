package com.flowpay.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update: a null field means "leave unchanged". An empty string clears an optional address field.
 * {@code email}, {@code phoneNumber} and {@code country} are declared only so an attempt to change them can
 * be rejected with a clear message; they are never applied.
 */
public record UpdateCustomerProfileRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String firstName,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String lastName,
        @Size(max = 150) String addressLine1,
        @Size(max = 150) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Size(max = 20) String postalCode,
        String email,
        String phoneNumber,
        String country) {
}
