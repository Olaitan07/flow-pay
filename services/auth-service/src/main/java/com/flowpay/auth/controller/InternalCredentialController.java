package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.CreateCredentialRequest;
import com.flowpay.auth.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by user-service during registration. Protected by the internal API key; not exposed by the gateway. */
@RestController
@RequestMapping("/internal/credentials")
public class InternalCredentialController {

    private final AuthenticationService authenticationService;

    public InternalCredentialController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping
    public ResponseEntity<Void> createCredential(@Valid @RequestBody CreateCredentialRequest request) {
        boolean created = authenticationService.createCredential(request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
}
