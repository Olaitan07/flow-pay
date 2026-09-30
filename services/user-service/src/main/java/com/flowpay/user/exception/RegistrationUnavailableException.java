package com.flowpay.user.exception;

public class RegistrationUnavailableException extends RuntimeException {

    public RegistrationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
