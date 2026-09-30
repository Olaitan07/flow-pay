package com.flowpay.user.service.impl;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.entity.Customer;
import com.flowpay.user.exception.DuplicateCustomerException;
import com.flowpay.user.repository.CustomerRepository;
import com.flowpay.user.service.CustomerService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomerServiceImpl(CustomerRepository customerRepository, PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public CustomerResponse registerCustomer(RegisterCustomerRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phoneNumber = request.phoneNumber().trim();

        // Friendly fast-path. The unique constraints below remain the real guarantee.
        if (customerRepository.existsByEmail(email)) {
            throw new DuplicateCustomerException("A customer with this email already exists");
        }
        if (customerRepository.existsByPhoneNumber(phoneNumber)) {
            throw new DuplicateCustomerException("A customer with this phone number already exists");
        }

        Customer customer = new Customer(request.firstName().trim(), request.lastName().trim(), email,
                phoneNumber, passwordEncoder.encode(request.password()), request.country());
        Customer saved;
        try {
            saved = customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            // Two simultaneous registrations passed the checks above; the database rejected the second.
            throw new DuplicateCustomerException("A customer with this email or phone number already exists");
        }

        log.info("Customer registered customerId={} status={}", saved.getId(), saved.getStatus());
        return CustomerResponse.from(saved);
    }
}
