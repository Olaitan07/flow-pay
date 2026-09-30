package com.flowpay.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.entity.Customer;
import com.flowpay.user.entity.CustomerStatus;
import com.flowpay.user.exception.DuplicateCustomerException;
import com.flowpay.user.repository.CustomerRepository;
import com.flowpay.user.service.impl.CustomerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock CustomerRepository customerRepository;
    @Mock PasswordEncoder passwordEncoder;

    CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(customerRepository, passwordEncoder);
    }

    private RegisterCustomerRequest request() {
        return new RegisterCustomerRequest(" Ada ", "Obi", "Ada.Obi@Example.COM", "+2348012345678",
                "Sup3rSecret", "NG");
    }

    @Test
    void registerCustomer_createsPendingCustomerWithNormalisedEmailAndHashedPassword() {
        when(passwordEncoder.encode("Sup3rSecret")).thenReturn("hashed");
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

        CustomerResponse response = service.registerCustomer(request());

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada.obi@example.com");
        assertThat(saved.getValue().getFirstName()).isEqualTo("Ada");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(CustomerStatus.PENDING_VERIFICATION);
    }

    @Test
    void registerCustomer_rejectsDuplicateEmail() {
        when(customerRepository.existsByEmail("ada.obi@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerCustomer(request()))
                .isInstanceOf(DuplicateCustomerException.class);
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCustomer_rejectsDuplicatePhoneNumber() {
        when(customerRepository.existsByPhoneNumber("+2348012345678")).thenReturn(true);

        assertThatThrownBy(() -> service.registerCustomer(request()))
                .isInstanceOf(DuplicateCustomerException.class);
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerCustomer_translatesDatabaseUniqueViolationIntoDuplicate() {
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(customerRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_customers_email"));

        assertThatThrownBy(() -> service.registerCustomer(request()))
                .isInstanceOf(DuplicateCustomerException.class);
    }
}
