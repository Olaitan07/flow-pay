package com.flowpay.notification.exception;

public class DeliveryFailedException extends RuntimeException {

    public DeliveryFailedException(Throwable cause) {
        super("The message could not be delivered", cause);
    }
}
