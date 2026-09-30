package com.flowpay.auth.exception;

public class MfaAlreadyEnabledException extends RuntimeException {

    public MfaAlreadyEnabledException() {
        super("Two-step verification is already enabled");
    }
}
