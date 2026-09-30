package com.flowpay.auth.exception;

public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        super("Code is invalid or has expired");
    }
}
