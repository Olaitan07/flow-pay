package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.PasswordResetConfirmRequest;
import com.flowpay.auth.dto.request.PasswordResetRequest;
import com.flowpay.auth.dto.response.MessageResponse;
import com.flowpay.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /** Same answer for every email, so this cannot be used to discover who has an account. */
    @PostMapping("/request")
    public ResponseEntity<MessageResponse> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MessageResponse("If an account exists for that email, a reset link has been sent."));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
