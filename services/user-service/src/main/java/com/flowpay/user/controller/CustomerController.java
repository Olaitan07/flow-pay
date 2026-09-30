package com.flowpay.user.controller;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.service.CustomerService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) {
        CustomerResponse created = customerService.registerCustomer(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }
}
