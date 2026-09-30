package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.DisableMfaRequest;
import com.flowpay.auth.dto.request.VerifyOtpRequest;
import com.flowpay.auth.dto.response.MfaChallengeResponse;
import com.flowpay.auth.exception.AuthenticationRequiredException;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Turning two-step verification on or off. The caller must be signed in (identity set by the gateway). */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private static final String CUSTOMER = "X-Authenticated-Customer-Id";

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    /** Emails a code first, so a customer cannot lock themselves out with an address that does not work. */
    @PostMapping("/enable/request")
    public MfaChallengeResponse requestEnable(@RequestHeader(name = CUSTOMER, required = false) UUID customerId) {
        LoginOutcome.MfaRequired challenge = mfaService.requestEnable(require(customerId));
        return MfaChallengeResponse.of(challenge.challengeId(), challenge.expiresInSeconds());
    }

    @PostMapping("/enable/confirm")
    public ResponseEntity<Void> confirmEnable(@RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                              @Valid @RequestBody VerifyOtpRequest request) {
        mfaService.confirmEnable(require(customerId), request.challengeId(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                        @Valid @RequestBody DisableMfaRequest request) {
        mfaService.disable(require(customerId), request.password());
        return ResponseEntity.noContent().build();
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
        return customerId;
    }
}
