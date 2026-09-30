package com.flowpay.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.user.AbstractIntegrationTest;
import com.flowpay.user.repository.CustomerRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class CustomerProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String HEADER = "X-Authenticated-Customer-Id";

    @Autowired MockMvc mockMvc;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    String customerId;

    @BeforeEach
    void registerCustomer() throws Exception {
        customerRepository.deleteAll();
        String response = mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Ada","lastName":"Obi","email":"ada@example.com",
                         "phoneNumber":"+2348012345678","password":"Sup3rSecret","country":"NG"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        customerId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions patchProfile(String requester, String json)
            throws Exception {
        var request = patch("/api/v1/users/" + customerId).contentType(MediaType.APPLICATION_JSON).content(json);
        if (requester != null) request.header(HEADER, requester);
        return mockMvc.perform(request);
    }

    @Test
    void customerCanViewOwnProfile_withoutSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + customerId).header(HEADER, customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void viewWithoutIdentity_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + customerId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void viewingSomeoneElsesProfile_isIndistinguishableFromANonExistentOne() throws Exception {
        String stranger = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/v1/users/" + customerId).header(HEADER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CUSTOMER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/users/" + stranger).header(HEADER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void malformedIdentifiers_return400() throws Exception {
        mockMvc.perform(get("/api/v1/users/not-a-uuid").header(HEADER, customerId))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/users/" + customerId).header(HEADER, "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCanUpdateAddress_andItPersists() throws Exception {
        patchProfile(customerId, """
                {"addressLine1":"12 Marina","city":"Lagos","state":"Lagos","postalCode":"101233"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressLine1").value("12 Marina"))
                .andExpect(jsonPath("$.city").value("Lagos"));

        mockMvc.perform(get("/api/v1/users/" + customerId).header(HEADER, customerId))
                .andExpect(jsonPath("$.postalCode").value("101233"))
                .andExpect(jsonPath("$.firstName").value("Ada"));
    }

    @Test
    void customerCanFixNameBeforeVerification() throws Exception {
        patchProfile(customerId, "{\"firstName\":\"Adaeze\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Adaeze"))
                .andExpect(jsonPath("$.lastName").value("Obi"));
    }

    @Test
    void nameIsLockedOnceAccountIsVerified() throws Exception {
        jdbcTemplate.update("update customers set status = 'ACTIVE'");

        patchProfile(customerId, "{\"firstName\":\"Adaeze\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PROFILE_FIELD_LOCKED"));
        patchProfile(customerId, "{\"city\":\"Abuja\"}")
                .andExpect(status().isOk());

        assertThat(customerRepository.findAll().getFirst().getFirstName()).isEqualTo("Ada");
    }

    @Test
    void emailPhoneAndCountryCannotBeChanged() throws Exception {
        for (String body : new String[] {"{\"email\":\"new@example.com\"}",
                "{\"phoneNumber\":\"+2348099999999\"}", "{\"country\":\"GH\"}"}) {
            patchProfile(customerId, body)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("PROFILE_FIELD_LOCKED"));
        }
        var stored = customerRepository.findAll().getFirst();
        assertThat(stored.getEmail()).isEqualTo("ada@example.com");
        assertThat(stored.getCountry()).isEqualTo("NG");
    }

    @Test
    void suspendedAccountCannotBeEdited() throws Exception {
        jdbcTemplate.update("update customers set status = 'SUSPENDED'");

        patchProfile(customerId, "{\"city\":\"Abuja\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_EDITABLE"));
    }

    @Test
    void updateWithInvalidValues_returns400_andWithoutIdentity_returns401() throws Exception {
        patchProfile(customerId, "{\"firstName\":\"   \"}").andExpect(status().isBadRequest());
        patchProfile(null, "{\"city\":\"Abuja\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCustomerCannotUpdateMyProfile() throws Exception {
        patchProfile(UUID.randomUUID().toString(), "{\"city\":\"Hacked\"}")
                .andExpect(status().isNotFound());

        assertThat(customerRepository.findAll().getFirst().getCity()).isNull();
    }
}
