package com.flowpay.auth.service;

public interface PasswordResetService {

    /** Always looks the same to the caller, whether or not the email has an account. */
    void requestReset(String email);

    void confirmReset(String token, String newPassword);
}
