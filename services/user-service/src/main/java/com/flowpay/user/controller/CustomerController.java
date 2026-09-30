package com.flowpay.user.controller;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.request.UpdateCustomerProfileRequest;
import com.flowpay.user.dto.response.CustomerProfileResponse;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.service.CustomerService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class CustomerController {

    /**
     * Interim stand-in for authentication. The gateway will set this header from a verified token in
     * Epic 2 and strips any value a client sends; until then it is only trustworthy on internal calls.
     */
    static final String AUTHENTICATED_CUSTOMER_HEADER = "X-Authenticated-Customer-Id";

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) {
        CustomerResponse created = customerService.registerCustomer(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public CustomerProfileResponse viewProfile(
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID requesterId,
            @PathVariable UUID id) {
        return customerService.viewProfile(requesterId, id);
    }

    @PatchMapping("/{id}")
    public CustomerProfileResponse updateProfile(
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID requesterId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerProfileRequest request) {
        return customerService.updateProfile(requesterId, id, request);
    }
}
