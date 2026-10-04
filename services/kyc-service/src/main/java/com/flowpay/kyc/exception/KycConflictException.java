package com.flowpay.kyc.exception;

public class KycConflictException extends RuntimeException {

    private final String errorCode;

    public KycConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
