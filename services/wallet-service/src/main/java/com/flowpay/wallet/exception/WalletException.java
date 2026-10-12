package com.flowpay.wallet.exception;

import org.springframework.http.HttpStatus;

/** Every business refusal: an HTTP status, a stable machine-readable code, and a human message. */
public class WalletException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public WalletException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

    public static WalletException notFound() {
        return new WalletException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found");
    }

    public static WalletException unauthenticated() {
        return new WalletException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
    }

    public static WalletException conflict(String code, String message) {
        return new WalletException(HttpStatus.CONFLICT, code, message);
    }

    public static WalletException rule(String code, String message) {
        return new WalletException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
