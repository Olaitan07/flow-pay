package com.flowpay.transaction.exception;

import org.springframework.http.HttpStatus;

/** Every business refusal: an HTTP status, a stable machine-readable code, and a human message. */
public class TransactionException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public TransactionException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

    public static TransactionException unauthenticated() {
        return new TransactionException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
    }

    public static TransactionException fundingNotFound() {
        return new TransactionException(HttpStatus.NOT_FOUND, "FUNDING_NOT_FOUND", "Funding not found");
    }

    public static TransactionException transferNotFound() {
        return new TransactionException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
    }

    public static TransactionException conflict(String code, String message) {
        return new TransactionException(HttpStatus.CONFLICT, code, message);
    }

    public static TransactionException rule(String code, String message) {
        return new TransactionException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static TransactionException unavailable(String code, String message) {
        return new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
