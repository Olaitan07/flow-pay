package com.flowpay.auth.exception;

public class DuplicateCredentialException extends RuntimeException {

    public DuplicateCredentialException() {
        super("Credentials already exist for this customer or email");
    }
}
