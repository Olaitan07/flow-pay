package com.flowpay.notification.dto.request;

import com.flowpay.notification.entity.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

@Schema(description = "An email to send using one of the built-in templates.")
public record SendEmailRequest(
        @Schema(description = "Which template to use. PASSWORD_RESET needs resetLink and expiresInMinutes; "
                + "LOGIN_OTP and MFA_ENABLE_OTP need code and expiresInMinutes; PASSWORD_CHANGED needs nothing.",
                example = "LOGIN_OTP")
        @NotNull NotificationType type,
        @Schema(description = "Recipient address. Line breaks are rejected.", example = "ada.obi@example.com")
        @NotBlank @Email @Size(max = 254)
        @Pattern(regexp = "^[^\\r\\n]+$", message = "must not contain line breaks")
        String recipient,
        @Schema(description = "Values for the template placeholders.",
                example = "{\"code\": \"482913\", \"expiresInMinutes\": \"5\"}")
        @Size(max = 10) Map<String, @Size(max = 500) String> variables) {

    /** Variables can hold one-time codes and reset links, so they are kept out of logs. */
    @Override
    public String toString() {
        return "SendEmailRequest[type=" + type + "]";
    }
}
