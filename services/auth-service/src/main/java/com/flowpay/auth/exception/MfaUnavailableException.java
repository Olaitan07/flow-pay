package com.flowpay.auth.exception;

public class MfaUnavailableException extends RuntimeException {

    public MfaUnavailableException(Throwable cause) {
        super("Could not send the verification code. Please try again.", cause);
    }
}
