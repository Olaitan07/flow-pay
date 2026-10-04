package com.flowpay.kyc.exception;

public class KycNotFoundException extends RuntimeException {

    private final String errorCode;

    public KycNotFoundException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
