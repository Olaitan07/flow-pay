package com.flowpay.auth.exception;

/** One message for every login failure, so responses never reveal whether an account exists or is locked. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
