package com.flowpay.user.service.impl;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.request.UpdateCustomerProfileRequest;
import com.flowpay.user.dto.response.CustomerProfileResponse;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.entity.Customer;
import com.flowpay.user.exception.AuthenticationRequiredException;
import com.flowpay.user.exception.CustomerNotFoundException;
import com.flowpay.user.exception.DuplicateCustomerException;
import com.flowpay.user.exception.ProfileUpdateNotAllowedException;
import com.flowpay.user.repository.CustomerRepository;
import com.flowpay.user.service.CredentialClient;
import com.flowpay.user.service.CustomerService;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final CredentialClient credentialClient;

    public CustomerServiceImpl(CustomerRepository customerRepository, CredentialClient credentialClient) {
        this.customerRepository = customerRepository;
        this.credentialClient = credentialClient;
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
                phoneNumber, request.country());
        Customer saved;
        try {
            saved = customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            // Two simultaneous registrations passed the checks above; the database rejected the second.
            throw new DuplicateCustomerException("A customer with this email or phone number already exists");
        }


        // Runs inside the same transaction: if auth-service fails, the exception rolls the customer back, so
        // an account never exists without a way to log in. (Residual risk: auth-service succeeds but the
        // final commit fails, leaving an orphan credential; reconciliation covers that later.)
        credentialClient.createCredentials(saved.getId(), email, request.password());

        log.info("Customer registered customerId={} status={}", saved.getId(), saved.getStatus());
        return CustomerResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerProfileResponse viewProfile(UUID requesterId, UUID customerId) {
        return CustomerProfileResponse.from(loadOwnCustomer(requesterId, customerId));
    }

    @Override
    @Transactional
    public CustomerProfileResponse updateProfile(UUID requesterId, UUID customerId,
                                                 UpdateCustomerProfileRequest request) {
        Customer customer = loadOwnCustomer(requesterId, customerId);

        rejectProtectedFieldChanges(request);
        if (!customer.canEditContactDetails()) {
            throw new ProfileUpdateNotAllowedException("ACCOUNT_NOT_EDITABLE",
                    "The profile cannot be changed while the account is " + customer.getStatus());
        }

        if (request.firstName() != null || request.lastName() != null) {
            if (!customer.canEditName()) {
                throw new ProfileUpdateNotAllowedException("PROFILE_FIELD_LOCKED",
                        "Name cannot be changed once the account is verified");
            }
            customer.changeName(
                    request.firstName() != null ? request.firstName().trim() : customer.getFirstName(),
                    request.lastName() != null ? request.lastName().trim() : customer.getLastName());
        }

        customer.changeAddress(
                merge(request.addressLine1(), customer.getAddressLine1()),
                merge(request.addressLine2(), customer.getAddressLine2()),
                merge(request.city(), customer.getCity()),
                merge(request.state(), customer.getState()),
                merge(request.postalCode(), customer.getPostalCode()));

        Customer saved = customerRepository.saveAndFlush(customer);
        log.info("Customer profile updated customerId={}", saved.getId());
        return CustomerProfileResponse.from(saved);
    }

    /**
     * Customers may only reach their own profile. Any other id gets the same "not found" as a missing one,
     * so the API never confirms which customers exist.
     */
    private Customer loadOwnCustomer(UUID requesterId, UUID customerId) {
        if (requesterId == null) {
            throw new AuthenticationRequiredException();
        }
        if (!requesterId.equals(customerId)) {
            throw new CustomerNotFoundException();
        }
        return customerRepository.findById(customerId).orElseThrow(CustomerNotFoundException::new);
    }

    private void rejectProtectedFieldChanges(UpdateCustomerProfileRequest request) {
        if (request.email() != null) {
            throw new ProfileUpdateNotAllowedException("PROFILE_FIELD_LOCKED",
                    "Email cannot be changed here; it requires verification");
        }
        if (request.phoneNumber() != null) {
            throw new ProfileUpdateNotAllowedException("PROFILE_FIELD_LOCKED",
                    "Phone number cannot be changed here; it requires verification");
        }
        if (request.country() != null) {
            throw new ProfileUpdateNotAllowedException("PROFILE_FIELD_LOCKED",
                    "Country cannot be changed here; it requires verification");
        }
    }

    /** null keeps the current value, blank clears it, anything else replaces it. */
    private static String merge(String requested, String current) {
        if (requested == null) {
            return current;
        }
        String trimmed = requested.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
