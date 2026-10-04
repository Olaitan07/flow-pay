package com.flowpay.kyc.exception;

public class InvalidKycRequestException extends RuntimeException {

    private final String errorCode;

    public InvalidKycRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
