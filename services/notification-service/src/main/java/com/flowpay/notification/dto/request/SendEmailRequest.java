package com.flowpay.notification.dto.request;

import com.flowpay.notification.entity.NotificationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record SendEmailRequest(
        @NotNull NotificationType type,
        @NotBlank @Email @Size(max = 254)
        @Pattern(regexp = "^[^\\r\\n]+$", message = "must not contain line breaks")
        String recipient,
        @Size(max = 10) Map<String, @Size(max = 500) String> variables) {

    /** Variables can hold one-time codes and reset links, so they are kept out of logs. */
    @Override
    public String toString() {
        return "SendEmailRequest[type=" + type + "]";
    }
}
