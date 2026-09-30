package com.flowpay.user.exception;

public class ProfileUpdateNotAllowedException extends RuntimeException {

    private final String errorCode;

    public ProfileUpdateNotAllowedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
