package com.flowpay.kyc.exception;

public class KycRuleViolationException extends RuntimeException {

    private final String errorCode;

    public KycRuleViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
