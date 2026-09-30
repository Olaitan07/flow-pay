package com.flowpay.user.dto.response;

import com.flowpay.user.entity.Customer;
import com.flowpay.user.entity.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String country,
        CustomerStatus status,
        Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getFirstName(), customer.getLastName(),
                customer.getEmail(), customer.getPhoneNumber(), customer.getCountry(),
                customer.getStatus(), customer.getCreatedAt());
    }
}
