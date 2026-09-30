package com.flowpay.auth.exception;

public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("Password reset link is invalid or has expired");
    }
}
