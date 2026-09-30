package com.flowpay.user.dto.response;

import com.flowpay.user.entity.Customer;
import com.flowpay.user.entity.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String country,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerProfileResponse from(Customer c) {
        return new CustomerProfileResponse(c.getId(), c.getFirstName(), c.getLastName(), c.getEmail(),
                c.getPhoneNumber(), c.getCountry(), c.getAddressLine1(), c.getAddressLine2(), c.getCity(),
                c.getState(), c.getPostalCode(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
