package com.flowpay.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowpay.user.dto.request.UpdateCustomerProfileRequest;
import com.flowpay.user.entity.Customer;
import com.flowpay.user.exception.AuthenticationRequiredException;
import com.flowpay.user.exception.CustomerNotFoundException;
import com.flowpay.user.exception.ProfileUpdateNotAllowedException;
import com.flowpay.user.repository.CustomerRepository;
import com.flowpay.user.service.impl.CustomerServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomerProfileServiceTest {

    @Mock CustomerRepository customerRepository;
    @Mock CredentialClient credentialClient;

    CustomerServiceImpl service;
    Customer customer;
    UUID id;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(customerRepository, credentialClient);
        customer = new Customer("Ada", "Obi", "ada@example.com", "+2348012345678", "NG");
        id = customer.getId();
        lenient().when(customerRepository.findById(id)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static UpdateCustomerProfileRequest update(String first, String last, String line1, String city) {
        return new UpdateCustomerProfileRequest(first, last, line1, null, city, null, null, null, null, null);
    }

    private static UpdateCustomerProfileRequest updateProtected(String email, String phone, String country) {
        return new UpdateCustomerProfileRequest(null, null, null, null, null, null, null, email, phone, country);
    }

    private void makeStatus(String status) {
        ReflectionTestUtils.setField(customer, "status",
                com.flowpay.user.entity.CustomerStatus.valueOf(status));
    }

    @Test
    void viewProfile_returnsOwnProfile() {
        assertThat(service.viewProfile(id, id).email()).isEqualTo("ada@example.com");
    }

    @Test
    void viewProfile_withoutIdentity_isUnauthenticated() {
        assertThatThrownBy(() -> service.viewProfile(null, id)).isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void viewProfile_ofAnotherCustomer_looksLikeNotFound_withoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.viewProfile(UUID.randomUUID(), id))
                .isInstanceOf(CustomerNotFoundException.class);
        verify(customerRepository, never()).findById(any());
    }

    @Test
    void updateProfile_changesNameAndAddress_whilePendingVerification() {
        var result = service.updateProfile(id, id, update("Adaeze", null, "12 Marina", "Lagos"));

        assertThat(result.firstName()).isEqualTo("Adaeze");
        assertThat(result.lastName()).isEqualTo("Obi");
        assertThat(result.addressLine1()).isEqualTo("12 Marina");
        assertThat(result.city()).isEqualTo("Lagos");
    }

    @Test
    void updateProfile_keepsOmittedAddressFields_andClearsBlankOnes() {
        service.updateProfile(id, id, update(null, null, "12 Marina", "Lagos"));

        var result = service.updateProfile(id, id, update(null, null, null, "  "));

        assertThat(result.addressLine1()).isEqualTo("12 Marina");
        assertThat(result.city()).isNull();
    }

    @Test
    void updateProfile_locksNameOnceVerified_butStillAllowsAddress() {
        makeStatus("ACTIVE");

        assertThatThrownBy(() -> service.updateProfile(id, id, update("Adaeze", null, null, null)))
                .isInstanceOfSatisfying(ProfileUpdateNotAllowedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PROFILE_FIELD_LOCKED"));
        assertThat(service.updateProfile(id, id, update(null, null, "5 Ring Road", null)).addressLine1())
                .isEqualTo("5 Ring Road");
    }

    @Test
    void updateProfile_rejectsEmailPhoneAndCountryChanges() {
        for (var request : new UpdateCustomerProfileRequest[] {
                updateProtected("new@example.com", null, null),
                updateProtected(null, "+2348099999999", null),
                updateProtected(null, null, "GH")}) {
            assertThatThrownBy(() -> service.updateProfile(id, id, request))
                    .isInstanceOfSatisfying(ProfileUpdateNotAllowedException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("PROFILE_FIELD_LOCKED"));
        }
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateProfile_isRefusedForSuspendedBlockedAndClosedAccounts() {
        for (String status : new String[] {"SUSPENDED", "BLOCKED", "CLOSED"}) {
            makeStatus(status);
            assertThatThrownBy(() -> service.updateProfile(id, id, update(null, null, "1 Street", null)))
                    .isInstanceOfSatisfying(ProfileUpdateNotAllowedException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("ACCOUNT_NOT_EDITABLE"));
        }
    }

    @Test
    void updateProfile_ofAnotherCustomer_isNotFound() {
        assertThatThrownBy(() -> service.updateProfile(UUID.randomUUID(), id, update("X", null, null, null)))
                .isInstanceOf(CustomerNotFoundException.class);
        verify(customerRepository, never()).saveAndFlush(any());
    }
}
