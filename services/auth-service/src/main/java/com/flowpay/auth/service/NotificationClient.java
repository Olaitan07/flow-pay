package com.flowpay.auth.service;

import java.util.Map;

/** Asks notification-service to email a customer. Codes and links travel only on this protected call. */
public interface NotificationClient {

    enum Type { PASSWORD_RESET, PASSWORD_CHANGED, LOGIN_OTP, MFA_ENABLE_OTP }

    /** @throws com.flowpay.auth.exception.NotificationFailedException if the message could not be sent */
    void send(Type type, String recipientEmail, Map<String, String> variables);
}
