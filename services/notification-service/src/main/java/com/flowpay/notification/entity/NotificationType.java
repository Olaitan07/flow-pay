package com.flowpay.notification.entity;

import java.util.List;
import java.util.Map;

/** Every email FlowPay can send. Wording lives here, so callers only supply the variable parts. */
public enum NotificationType {

    PASSWORD_RESET("Reset your FlowPay password", List.of("resetLink", "expiresInMinutes"), """
            We received a request to reset your FlowPay password.

            Use this link within {expiresInMinutes} minutes:
            {resetLink}

            If you did not ask for this, ignore this email. Your password has not changed."""),

    PASSWORD_CHANGED("Your FlowPay password was changed", List.of(), """
            The password on your FlowPay account was just changed and all devices were signed out.

            If this was not you, contact FlowPay support immediately."""),

    LOGIN_OTP("Your FlowPay sign-in code", List.of("code", "expiresInMinutes"), """
            Your FlowPay sign-in code is: {code}

            It expires in {expiresInMinutes} minutes. Never share this code. FlowPay staff will never ask for it.

            If you did not try to sign in, reset your password."""),

    MFA_ENABLE_OTP("Confirm two-step verification", List.of("code", "expiresInMinutes"), """
            Your FlowPay confirmation code is: {code}

            Enter it to turn on two-step verification. It expires in {expiresInMinutes} minutes.
            Never share this code.""");

    private final String subject;
    private final List<String> requiredVariables;
    private final String bodyTemplate;

    NotificationType(String subject, List<String> requiredVariables, String bodyTemplate) {
        this.subject = subject;
        this.requiredVariables = requiredVariables;
        this.bodyTemplate = bodyTemplate;
    }

    public String subject() {
        return subject;
    }

    public List<String> requiredVariables() {
        return requiredVariables;
    }

    public String render(Map<String, String> variables) {
        String body = bodyTemplate;
        for (String name : requiredVariables) {
            body = body.replace("{" + name + "}", variables.get(name));
        }
        return body;
    }
}
