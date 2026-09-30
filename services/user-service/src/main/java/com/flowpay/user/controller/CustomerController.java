package com.flowpay.user.controller;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.request.UpdateCustomerProfileRequest;
import com.flowpay.user.dto.response.CustomerProfileResponse;
import com.flowpay.user.dto.response.CustomerResponse;
import com.flowpay.user.dto.response.ErrorResponse;
import com.flowpay.user.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "1. Customers", description = "Register an account, then view and maintain your own profile.")
public class CustomerController {

    /**
     * Set by the gateway from a verified access token; the gateway removes any value a client sends.
     * Calling this service directly (port 8082) bypasses that protection, which is only acceptable in development.
     */
    static final String AUTHENTICATED_CUSTOMER_HEADER = "X-Authenticated-Customer-Id";

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(operationId = "registerCustomer", summary = "Register a new customer",
            description = """
                    Creates a FlowPay account. **Public** (no token needed). Limited to 5 requests per minute per IP address.

                    ### How it works
                    1. The request is validated (see the field rules in the schema).
                    2. The email is lowercased. Email and phone number must each be unique.
                    3. The customer is saved with a generated `id` and status `PENDING_VERIFICATION`.
                    4. The password is sent to **auth-service**, which hashes it (BCrypt) and stores it. This service never stores or returns it.
                    5. If auth-service is unavailable the whole registration is rolled back and you get `503`: there is never an account without a way to log in.

                    ### How to test
                    1. Send the example body. Expect **201**, and copy the `id` from the response.
                    2. Send the exact same body again. Expect **409 DUPLICATE_CUSTOMER** (same email, in any letter case).
                    3. Change only the email and keep the phone number. Still **409** (phone must be unique too).
                    4. Send `{}` or a weak password such as `weak`. Expect **400 VALIDATION_ERROR** listing each bad field.
                    5. Send 6+ requests within a minute from one machine. Expect **429** with a `Retry-After` header.
                    6. Next step: log in with `POST /api/v1/auth/login`.
                    """)
    @ApiResponse(responseCode = "201", description = "Account created. The Location header points at the profile URL.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or MALFORMED_REQUEST.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_CUSTOMER: email or phone number already registered.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED. Wait for Retry-After seconds.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "REGISTRATION_UNAVAILABLE: auth-service could not store the login. Nothing was created; retry.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<CustomerResponse> registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) {
        CustomerResponse created = customerService.registerCustomer(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "viewProfile", summary = "View my profile",
            description = """
                    Returns the signed-in customer's own profile. **Requires a bearer token.**

                    ### How it works
                    The gateway verifies the access token and tells this service who is calling. You can only read **your own** profile:
                    any other id, and any id that does not exist, returns the same `404`, so the API never reveals which customers exist.

                    ### How to test
                    1. Register (`POST /api/v1/users`) and log in (`POST /api/v1/auth/login`). Put the `accessToken` in the Authorize box (or the `Authorization: Bearer ...` header).
                    2. Call this endpoint with the `id` from registration. Expect **200** and your profile.
                    3. Call it with a different UUID, e.g. `00000000-0000-0000-0000-000000000001`. Expect **404 CUSTOMER_NOT_FOUND**.
                    4. Call it with no token. Expect **401 UNAUTHENTICATED**.
                    5. Call it with `not-a-uuid`. Expect **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "Your profile.")
    @ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST: the id is not a valid UUID.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: missing, expired or invalid access token.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND: not your profile, or no such customer.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CustomerProfileResponse viewProfile(
            @Parameter(hidden = true)
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID requesterId,
            @Parameter(description = "Your customer id (from registration).", example = "6fbf8203-6ee0-4b39-aa85-d721d712480c")
            @PathVariable UUID id) {
        return customerService.viewProfile(requesterId, id);
    }

    @PatchMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "updateProfile", summary = "Update my profile",
            description = """
                    Changes the allowed parts of your profile. **Requires a bearer token.** Send only the fields you want to change.

                    ### What you can change
                    | Field | Rule |
                    |---|---|
                    | `addressLine1`, `addressLine2`, `city`, `state`, `postalCode` | While the account is `PENDING_VERIFICATION` or `ACTIVE`. An empty string clears the field. |
                    | `firstName`, `lastName` | Only while `PENDING_VERIFICATION`. They lock once the account is verified. |
                    | `email`, `phoneNumber`, `country` | **Never** here (they need verification). Sending them returns `422 PROFILE_FIELD_LOCKED`. |
                    | Any field on a `SUSPENDED`, `BLOCKED` or `CLOSED` account | `422 ACCOUNT_NOT_EDITABLE`. |

                    ### How to test
                    1. Sign in and use your `id`. Send `{"city": "Lagos", "addressLine1": "12 Marina Road"}`. Expect **200** with the new values.
                    2. Send `{"firstName": "Adaeze"}`. Expect **200** (a new account is still `PENDING_VERIFICATION`).
                    3. Send `{"email": "new@example.com"}`. Expect **422 PROFILE_FIELD_LOCKED**.
                    4. Send `{"city": ""}`. The city is cleared (`null`).
                    5. Send `{"firstName": "   "}`. Expect **400**.
                    6. Use another customer's id. Expect **404**. No token: **401**.
                    7. To see the name lock, set the account to ACTIVE in the database (there is no verification flow yet) and change `firstName` again: **422**.
                    """)
    @ApiResponse(responseCode = "200", description = "The updated profile.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or MALFORMED_REQUEST.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND: not your profile, or no such customer.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_UPDATE: another request changed the profile at the same moment; reload and retry.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "PROFILE_FIELD_LOCKED or ACCOUNT_NOT_EDITABLE.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CustomerProfileResponse updateProfile(
            @Parameter(hidden = true)
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID requesterId,
            @Parameter(description = "Your customer id.", example = "6fbf8203-6ee0-4b39-aa85-d721d712480c")
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerProfileRequest request) {
        return customerService.updateProfile(requesterId, id, request);
    }
}
