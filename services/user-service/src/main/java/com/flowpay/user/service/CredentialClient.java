package com.flowpay.user.service;

import java.util.UUID;

/** Asks auth-service, which owns credentials, to store a new customer's login. */
public interface CredentialClient {

    void createCredentials(UUID customerId, String email, String password);
}
