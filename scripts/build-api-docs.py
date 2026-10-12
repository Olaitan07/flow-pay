#!/usr/bin/env python3
"""Builds the FlowPay API documentation files from the running services.

Needs the stack running (docker compose up -d). It reads each service's generated OpenAPI spec, so the
documentation always comes from the annotations in the code, then writes:

  docs/api/flowpay-openapi.json            public API, as seen through the gateway (import into Postman / Swagger)
  docs/api/flowpay-internal-openapi.json   service-to-service endpoints (direct ports, internal API key)
  docs/api/flowpay.postman_collection.json runnable Postman collection with tests
  docs/api/flowpay-local.postman_environment.json
  services/api-gateway/src/main/resources/static/openapi/*.json   served by the gateway's Swagger UI

Usage: python3 scripts/build-api-docs.py
Override service URLs with USER_URL, AUTH_URL, NOTIFICATION_URL, KYC_URL, WALLET_URL, TRANSACTION_URL if you changed the ports.
"""
import json
import os
import shutil
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs", "api")
GATEWAY_STATIC = os.path.join(ROOT, "services", "api-gateway", "src", "main", "resources", "static", "openapi")

USER_URL = os.environ.get("USER_URL", "http://localhost:8082")
AUTH_URL = os.environ.get("AUTH_URL", "http://localhost:8081")
NOTIFICATION_URL = os.environ.get("NOTIFICATION_URL", "http://localhost:8086")
KYC_URL = os.environ.get("KYC_URL", "http://localhost:8087")
WALLET_URL = os.environ.get("WALLET_URL", "http://localhost:8083")
TRANSACTION_URL = os.environ.get("TRANSACTION_URL", "http://localhost:8084")
GATEWAY_URL = "http://localhost:8080"


def fetch(url):
    with urllib.request.urlopen(url + "/v3/api-docs", timeout=20) as response:
        return json.load(response)


def dump(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ----------------------------------------------------------------------------- OpenAPI

OVERVIEW = """\
FlowPay is a digital wallet platform. This is the **public API**, reached through the gateway at
`http://localhost:8080`.

## Quick start
1. **Register** - `POST /api/v1/users`
2. **Log in** - `POST /api/v1/auth/login` -> copy the `accessToken`
3. Click **Authorize** (top right) and paste the token. Protected endpoints (padlock icon) now work.
4. **View / update your profile** - `GET` / `PATCH /api/v1/users/{id}`
5. **Verify your identity** - `GET /api/v1/kyc`, then `POST /api/v1/kyc/tiers/1` and `/tiers/2`. Your verification level decides what you may do with money.
6. **Open a wallet** - `POST /api/v1/wallets` (needs KYC level 1), then `GET /api/v1/wallets` to see your balances.
7. **Add money** - `POST /api/v1/funding`, then follow it with `GET /api/v1/funding/{reference}`. Your wallet is credited once the payment is confirmed.

## How security works
* Only registration, login, refresh, logout, password reset and the health check are public.
* Everything else needs `Authorization: Bearer <accessToken>`. Tokens are JWTs signed with RS256 and last 15 minutes;
  renew them with `POST /api/v1/auth/refresh` (refresh tokens are single use).
* You only ever see and change **your own** data. Another customer's id returns `404`.
* Several endpoints are rate limited per IP address and return `429` with a `Retry-After` header when exceeded.
* Every error has the same shape: `{timestamp, status, error, message, path}`. Branch on `error`, not `message`.

## Emails in development
Password reset and two-step verification send real emails to a local inbox (Mailpit): http://localhost:8025.
"""

INTERNAL_OVERVIEW = """\
Service-to-service endpoints. They are **not** reachable through the gateway: call the services directly
(auth-service on port 8081, notification-service on port 8086, kyc-service on port 8087, wallet-service on port 8083, transaction-service on port 8084) with the `X-Internal-Api-Key` header
(`INTERNAL_API_KEY` from your `.env`). Intended for development and debugging only.
"""


def merge_specs(user, auth, notification, kyc, wallet, transaction):
    public_paths, internal_paths = {}, {}
    schemas, tags = {}, {}

    for name, spec, base in (("user", user, USER_URL), ("auth", auth, AUTH_URL),
                             ("notification", notification, NOTIFICATION_URL), ("kyc", kyc, KYC_URL),
                             ("wallet", wallet, WALLET_URL), ("transaction", transaction, TRANSACTION_URL)):
        for path, item in spec["paths"].items():
            if path.startswith("/internal"):
                item = dict(item)
                item["servers"] = [{"url": base, "description": f"{name}-service (direct)"}]
                internal_paths[path] = item
            else:
                public_paths[path] = item
        for schema_name, schema in spec.get("components", {}).get("schemas", {}).items():
            if schema_name in schemas and schemas[schema_name] != schema:
                sys.exit(f"Schema '{schema_name}' differs between services; rename one of them.")
            schemas[schema_name] = schema
        for tag in spec.get("tags", []):
            tags[tag["name"]] = tag

    def used_refs(paths):
        text = json.dumps(paths)
        return {n for n in schemas if f'"#/components/schemas/{n}"' in text}

    def closure(names):
        """Adds every schema that the given schemas refer to, directly or indirectly."""
        names = set(names)
        changed = True
        while changed:
            changed = False
            for n in list(names):
                for other in schemas:
                    if other not in names and f'"#/components/schemas/{other}"' in json.dumps(schemas[n]):
                        names.add(other)
                        changed = True
        return names

    def build(title, description, paths, servers, security_schemes):
        names = closure(used_refs(paths))
        used_tags = sorted({t for item in paths.values() for op in item.values() if isinstance(op, dict)
                            for t in op.get("tags", [])})
        return {
            "openapi": "3.1.0",
            "info": {"title": title, "version": "v1", "description": description},
            "servers": servers,
            "tags": [tags.get(t, {"name": t}) for t in used_tags],
            "paths": dict(sorted(paths.items())),
            "components": {
                "schemas": {n: schemas[n] for n in sorted(names)},
                "securitySchemes": security_schemes,
            },
        }

    bearer = {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT",
                             "description": "Access token from POST /api/v1/auth/login (or /login/verify)."}}
    api_key = {"internalApiKey": {"type": "apiKey", "in": "header", "name": "X-Internal-Api-Key",
                                  "description": "INTERNAL_API_KEY from .env."}}
    public = build("FlowPay API", OVERVIEW, public_paths,
                   [{"url": GATEWAY_URL, "description": "Local gateway (docker compose)"}], bearer)
    internal = build("FlowPay internal service APIs", INTERNAL_OVERVIEW, internal_paths,
                     [{"url": AUTH_URL, "description": "auth-service (direct)"}], api_key)
    return public, internal


def operations(*specs):
    found = {}
    for spec in specs:
        for path, item in spec["paths"].items():
            for method, op in item.items():
                if isinstance(op, dict) and "operationId" in op:
                    found[op["operationId"]] = {"method": method.upper(), "path": path,
                                                "summary": op.get("summary", ""),
                                                "description": op.get("description", "")}
    return found


# ----------------------------------------------------------------------------- Postman

def js(*lines):
    return [line for line in lines]


STATUS = lambda code, text: f'pm.test("{code} {text}", () => pm.response.to.have.status({code}));'
SAVE = lambda var, expr: f'pm.collectionVariables.set("{var}", {expr});'

DECODE_JWT = """function jwtPayload(token) {
    const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const text = (typeof atob === 'function') ? atob(part) : Buffer.from(part, 'base64').toString();
    return JSON.parse(text);
}"""

WAIT = lambda ms: f"""// Emails are sent in the background: give the mail server a moment before reading the inbox.
const wakeAt = Date.now() + {ms};
while (Date.now() < wakeAt) {{ /* wait */ }}"""


def request(folder, name, op=None, *, method=None, url=None, body=None, auth="bearer", headers=None,
            pre=None, tests=None, note="", description=None):
    return dict(folder=folder, name=name, op=op, method=method, url=url, body=body, auth=auth,
                headers=headers or [], pre=pre or [], tests=tests or [], note=note, description=description)


REG_BODY = {"firstName": "Ada", "lastName": "Obi", "email": "{{email}}", "phoneNumber": "{{phone}}",
            "password": "{{password}}", "country": "NG"}
LOGIN_BODY = {"email": "{{email}}", "password": "{{password}}"}


def build_requests():
    R = []
    F0 = "0. Start here (health and inbox)"
    R.append(request(F0, "Gateway health check", method="GET", url="{{baseUrl}}/actuator/health", auth="none",
        description="Confirms the gateway is up. If this fails, start the stack with `docker compose up --build -d`.",
        tests=js(STATUS(200, "OK"), 'pm.test("status is UP", () => pm.expect(pm.response.json().status).to.eql("UP"));')))
    R.append(request(F0, "Mailpit: empty the inbox", method="DELETE", url="{{mailpitUrl}}/api/v1/messages", auth="none",
        description="Helper. Mailpit is the local inbox that catches every email FlowPay sends (open http://localhost:8025 in a browser). "
                    "Emptying it first makes the later 'read the email' steps unambiguous.",
        tests=js(STATUS(200, "OK"))))

    F1 = "1. Register a customer"
    R.append(request(F1, "Register a customer", "registerCustomer", body=REG_BODY, auth="none",
        note="Each run generates a fresh email and phone number so you never hit 'duplicate' by accident. The generated values "
             "are stored in the collection variables `email`, `phone` and `customerId`.",
        pre=js('pm.collectionVariables.set("password", "Sup3rSecret");',
               'pm.collectionVariables.set("email", "ada." + Date.now() + "@example.com");',
               'pm.collectionVariables.set("phone", "+23480" + Math.floor(10000000 + Math.random() * 89999999));'),
        tests=js(STATUS(201, "Created"), "const body = pm.response.json();",
                 'pm.test("has a generated id and starts PENDING_VERIFICATION", () => { pm.expect(body.id).to.be.a("string"); pm.expect(body.status).to.eql("PENDING_VERIFICATION"); });',
                 'pm.test("email is stored in lowercase", () => pm.expect(body.email).to.eql(pm.collectionVariables.get("email").toLowerCase()));',
                 'pm.test("no password data in the response", () => { pm.expect(body).to.not.have.property("password"); pm.expect(body).to.not.have.property("passwordHash"); });',
                 'pm.test("Location header points at the new profile", () => pm.expect(pm.response.headers.get("Location")).to.include(body.id));',
                 SAVE("customerId", "body.id"))))
    R.append(request(F1, "Register the same customer again (expect 409)", "registerCustomer", body=REG_BODY, auth="none",
        note="**What this checks:** a second account with the same email must not be created.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is DUPLICATE_CUSTOMER", () => pm.expect(pm.response.json().error).to.eql("DUPLICATE_CUSTOMER"));')))
    R.append(request(F1, "Register with invalid data (expect 400)", "registerCustomer",
        body={"firstName": "", "lastName": "Obi", "email": "not-an-email", "phoneNumber": "0801", "password": "weak", "country": "Nigeria"},
        auth="none", note="**What this checks:** every bad field is reported in one response.",
        tests=js(STATUS(400, "Bad Request"), "const body = pm.response.json();",
                 'pm.test("error is VALIDATION_ERROR", () => pm.expect(body.error).to.eql("VALIDATION_ERROR"));',
                 'pm.test("names each bad field", () => ["firstName", "email", "phoneNumber", "password", "country"].forEach(f => pm.expect(body.message).to.include(f)));')))

    F2 = "2. Log in"
    R.append(request(F2, "Log in", "login", body=LOGIN_BODY, auth="none",
        note="Stores `accessToken` and `refreshToken` in collection variables. Later requests send the access token automatically.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();", DECODE_JWT,
                 'pm.test("returns an access token and a refresh token", () => { pm.expect(body.accessToken).to.be.a("string"); pm.expect(body.refreshToken).to.be.a("string"); pm.expect(body.tokenType).to.eql("Bearer"); });',
                 'pm.test("access token lasts 15 minutes", () => pm.expect(body.expiresIn).to.eql(900));',
                 'pm.test("token subject is the customer id", () => pm.expect(jwtPayload(body.accessToken).sub).to.eql(pm.collectionVariables.get("customerId")));',
                 SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F2, "Log in with the wrong password (expect 401)", "login",
        body={"email": "{{email}}", "password": "Wrong1234"}, auth="none",
        note="**What this checks:** a wrong password is refused with a generic message. The message is saved so the next request can prove it is identical for an unknown email. "
             "*Five wrong passwords in a row lock the account for 15 minutes - click Send five times to try it (a password reset unlocks it).*",
        tests=js(STATUS(401, "Unauthorized"), "const body = pm.response.json();",
                 'pm.test("error is INVALID_CREDENTIALS", () => pm.expect(body.error).to.eql("INVALID_CREDENTIALS"));',
                 SAVE("wrongPasswordMessage", "body.message"))))
    R.append(request(F2, "Log in with an unknown email (expect the identical 401)", "login",
        body={"email": "nobody@example.com", "password": "Wrong1234"}, auth="none",
        note="**What this checks:** the answer is indistinguishable from a wrong password, so the API cannot be used to discover who has an account.",
        tests=js(STATUS(401, "Unauthorized"),
                 'pm.test("same error and message as a wrong password", () => { const b = pm.response.json(); pm.expect(b.error).to.eql("INVALID_CREDENTIALS"); pm.expect(b.message).to.eql(pm.collectionVariables.get("wrongPasswordMessage")); });')))

    F3 = "3. My profile"
    R.append(request(F3, "View my profile", "viewProfile", url="{{baseUrl}}/api/v1/users/{{customerId}}",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("is my profile", () => { pm.expect(body.id).to.eql(pm.collectionVariables.get("customerId")); pm.expect(body.status).to.eql("PENDING_VERIFICATION"); });',
                 'pm.test("never contains password data", () => { pm.expect(body).to.not.have.property("password"); pm.expect(body).to.not.have.property("passwordHash"); });')))
    R.append(request(F3, "View someone else's profile (expect 404)", "viewProfile",
        url="{{baseUrl}}/api/v1/users/00000000-0000-0000-0000-000000000001",
        note="**What this checks:** you cannot read another customer, and the answer does not reveal whether that customer exists.",
        tests=js(STATUS(404, "Not Found"), 'pm.test("error is CUSTOMER_NOT_FOUND", () => pm.expect(pm.response.json().error).to.eql("CUSTOMER_NOT_FOUND"));')))
    R.append(request(F3, "View my profile without a token (expect 401)", "viewProfile", url="{{baseUrl}}/api/v1/users/{{customerId}}", auth="none",
        note="**What this checks:** the gateway refuses requests with no access token.",
        tests=js(STATUS(401, "Unauthorized"), 'pm.test("error is UNAUTHENTICATED", () => pm.expect(pm.response.json().error).to.eql("UNAUTHENTICATED"));')))
    R.append(request(F3, "Update my address", "updateProfile", url="{{baseUrl}}/api/v1/users/{{customerId}}", method="PATCH",
        body={"addressLine1": "12 Marina Road", "city": "Lagos", "state": "Lagos", "postalCode": "101233"},
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("address updated, name untouched", () => { pm.expect(body.city).to.eql("Lagos"); pm.expect(body.addressLine1).to.eql("12 Marina Road"); pm.expect(body.firstName).to.eql("Ada"); });')))
    R.append(request(F3, "Fix my first name (allowed while PENDING_VERIFICATION)", "updateProfile",
        url="{{baseUrl}}/api/v1/users/{{customerId}}", method="PATCH", body={"firstName": "Adaeze"},
        note="Names can change only before the account is verified.",
        tests=js(STATUS(200, "OK"), 'pm.test("first name changed", () => pm.expect(pm.response.json().firstName).to.eql("Adaeze"));')))
    R.append(request(F3, "Try to change my email (expect 422)", "updateProfile", url="{{baseUrl}}/api/v1/users/{{customerId}}",
        method="PATCH", body={"email": "new@example.com"},
        note="**What this checks:** verified identity fields (email, phone, country) cannot be changed through the profile endpoint.",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is PROFILE_FIELD_LOCKED", () => pm.expect(pm.response.json().error).to.eql("PROFILE_FIELD_LOCKED"));')))
    R.append(request(F3, "Clear my city (empty string)", "updateProfile", url="{{baseUrl}}/api/v1/users/{{customerId}}",
        method="PATCH", body={"city": ""},
        tests=js(STATUS(200, "OK"), 'pm.test("city cleared, other address fields kept", () => { const b = pm.response.json(); pm.expect(b.city).to.eql(null); pm.expect(b.postalCode).to.eql("101233"); });')))

    F4 = "4. Sessions (refresh and log out)"
    R.append(request(F4, "Refresh the access token", "refreshToken", body={"refreshToken": "{{refreshToken}}"}, auth="none",
        note="Returns a **new** refresh token and retires the old one. The old one is kept in `previousRefreshToken` for the next request.",
        pre=js('pm.collectionVariables.set("previousRefreshToken", pm.collectionVariables.get("refreshToken"));'),
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("got a different refresh token", () => pm.expect(body.refreshToken).to.not.eql(pm.collectionVariables.get("previousRefreshToken")));',
                 SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F4, "Reuse the OLD refresh token (expect 401)", "refreshToken", body={"refreshToken": "{{previousRefreshToken}}"}, auth="none",
        note="**What this checks:** a refresh token works only once. Presenting a used one is treated as possible theft and ends the whole session.",
        tests=js(STATUS(401, "Unauthorized"), 'pm.test("error is INVALID_REFRESH_TOKEN", () => pm.expect(pm.response.json().error).to.eql("INVALID_REFRESH_TOKEN"));')))
    R.append(request(F4, "The NEWER token is now dead too (expect 401)", "refreshToken", body={"refreshToken": "{{refreshToken}}"}, auth="none",
        note="**What this checks:** reuse revoked the whole session, including the newest token. You must log in again.",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F4, "Log in again (new session)", "login", body=LOGIN_BODY, auth="none",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();", SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F4, "Log out this session", "logout", body={"refreshToken": "{{refreshToken}}"}, auth="none",
        tests=js(STATUS(204, "No Content"))))
    R.append(request(F4, "Refresh after logging out (expect 401)", "refreshToken", body={"refreshToken": "{{refreshToken}}"}, auth="none",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F4, "Log in again (for log-out-everywhere)", "login", body=LOGIN_BODY, auth="none",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();", SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F4, "Log out everywhere", "logoutAll", body={},
        note="Needs the bearer token (sent automatically).",
        tests=js(STATUS(204, "No Content"))))
    R.append(request(F4, "Refresh after log-out-everywhere (expect 401)", "refreshToken", body={"refreshToken": "{{refreshToken}}"}, auth="none",
        tests=js(STATUS(401, "Unauthorized"))))

    F5 = "5. Password reset"
    R.append(request(F5, "Mailpit: empty the inbox", method="DELETE", url="{{mailpitUrl}}/api/v1/messages", auth="none",
        description="Helper: start from an empty inbox.", tests=js(STATUS(200, "OK"))))
    R.append(request(F5, "Request a reset link", "requestPasswordReset", body={"email": "{{email}}"}, auth="none",
        tests=js(STATUS(202, "Accepted"), "const body = pm.response.json();", SAVE("resetAckMessage", "body.message"))))
    R.append(request(F5, "Request a reset for an unknown email (identical answer)", "requestPasswordReset",
        body={"email": "ghost@example.com"}, auth="none",
        note="**What this checks:** the response is exactly the same as for a real account, so it reveals nothing.",
        tests=js(STATUS(202, "Accepted"), 'pm.test("same message as for a real account", () => pm.expect(pm.response.json().message).to.eql(pm.collectionVariables.get("resetAckMessage")));')))
    R.append(request(F5, "Mailpit: read the reset email and grab the token", method="GET", url="{{mailpitUrl}}/api/v1/message/latest", auth="none",
        description="Reads the newest email from the local inbox and extracts the `token=` value from the reset link into `resetToken`. "
                    "You can also open http://localhost:8025 and copy it by hand.",
        pre=js(WAIT(1500)),
        tests=js(STATUS(200, "OK"), "const mail = pm.response.json();",
                 'pm.test("sent to the customer", () => pm.expect(mail.To[0].Address).to.eql(pm.collectionVariables.get("email").toLowerCase()));',
                 'const match = /token=([A-Za-z0-9_-]+)/.exec(mail.Text);',
                 'pm.test("contains a reset link with a token", () => pm.expect(match, "no token= in the email").to.not.eql(null));',
                 'if (match) { pm.collectionVariables.set("resetToken", match[1]); }')))
    R.append(request(F5, "Mailpit: only the real account got an email", method="GET", url="{{mailpitUrl}}/api/v1/messages", auth="none",
        description="**What this checks:** the unknown address received nothing, even though the API answered the same way.",
        tests=js(STATUS(200, "OK"), 'pm.test("exactly one email was sent", () => pm.expect(pm.response.json().messages_count).to.eql(1));')))
    R.append(request(F5, "Set a new password with the token", "confirmPasswordReset",
        body={"token": "{{resetToken}}", "newPassword": "{{newPassword}}"}, auth="none",
        tests=js(STATUS(204, "No Content"))))
    R.append(request(F5, "Use the same link again (expect 400)", "confirmPasswordReset",
        body={"token": "{{resetToken}}", "newPassword": "An0therPass1"}, auth="none",
        note="**What this checks:** a reset link works only once.",
        tests=js(STATUS(400, "Bad Request"), 'pm.test("error is INVALID_RESET_TOKEN", () => pm.expect(pm.response.json().error).to.eql("INVALID_RESET_TOKEN"));')))
    R.append(request(F5, "Log in with the OLD password (expect 401)", "login", body=LOGIN_BODY, auth="none",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F5, "Log in with the NEW password", "login", body={"email": "{{email}}", "password": "{{newPassword}}"}, auth="none",
        note="Also switches the collection's `password` variable to the new password for the rest of the run.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"),
                 'pm.collectionVariables.set("password", pm.collectionVariables.get("newPassword"));')))

    F6 = "6. Two-step verification (email code)"
    R.append(request(F6, "Mailpit: empty the inbox", method="DELETE", url="{{mailpitUrl}}/api/v1/messages", auth="none",
        description="Helper: start from an empty inbox.", tests=js(STATUS(200, "OK"))))
    R.append(request(F6, "Start turning on two-step verification", "requestEnableMfa", body={},
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("a challenge was created", () => { pm.expect(body.mfaRequired).to.eql(true); pm.expect(body.challengeId).to.be.a("string"); });',
                 SAVE("challengeId", "body.challengeId"))))
    R.append(request(F6, "Mailpit: read the confirmation code", method="GET", url="{{mailpitUrl}}/api/v1/message/latest", auth="none",
        description="Reads the newest email and stores the 6-digit code in `otpCode`. You can also read it by hand at http://localhost:8025.",
        pre=js(WAIT(500)),
        tests=js(STATUS(200, "OK"), "const mail = pm.response.json();", 'const match = /code is: (\\d{6})/.exec(mail.Text);',
                 'pm.test("email contains a 6-digit code", () => pm.expect(match, "no code in the email").to.not.eql(null));',
                 'if (match) { pm.collectionVariables.set("otpCode", match[1]); }')))
    R.append(request(F6, "Confirm and turn on two-step verification", "confirmEnableMfa",
        body={"challengeId": "{{challengeId}}", "code": "{{otpCode}}"}, tests=js(STATUS(204, "No Content"))))
    R.append(request(F6, "Mailpit: empty the inbox (before logging in)", method="DELETE", url="{{mailpitUrl}}/api/v1/messages", auth="none",
        description="Helper.", tests=js(STATUS(200, "OK"))))
    R.append(request(F6, "Log in - now a code is required", "login", body=LOGIN_BODY, auth="none",
        note="With two-step verification on, a correct password no longer returns tokens. It returns a `challengeId` and emails a code.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("a code is required and no tokens are issued", () => { pm.expect(body.mfaRequired).to.eql(true); pm.expect(body).to.not.have.property("accessToken"); pm.expect(body).to.not.have.property("refreshToken"); });',
                 SAVE("challengeId", "body.challengeId"))))
    R.append(request(F6, "Mailpit: read the sign-in code", method="GET", url="{{mailpitUrl}}/api/v1/message/latest", auth="none",
        description="Stores the sign-in code in `otpCode` (also visible at http://localhost:8025).",
        pre=js(WAIT(500)),
        tests=js(STATUS(200, "OK"), "const mail = pm.response.json();", 'const match = /code is: (\\d{6})/.exec(mail.Text);',
                 'pm.test("email contains a 6-digit code", () => pm.expect(match, "no code in the email").to.not.eql(null));',
                 'if (match) { pm.collectionVariables.set("otpCode", match[1]); }')))
    R.append(request(F6, "Verify with a wrong code (expect 401)", "verifyLogin", body={"challengeId": "{{challengeId}}", "code": "{{wrongCode}}"}, auth="none",
        note="A wrong guess is refused. A code allows 5 guesses in total, and wrong guesses also count towards the account lockout.",
        pre=js('pm.collectionVariables.set("wrongCode", pm.collectionVariables.get("otpCode") === "000000" ? "111111" : "000000");'),
        tests=js(STATUS(401, "Unauthorized"), 'pm.test("error is INVALID_MFA_CODE", () => pm.expect(pm.response.json().error).to.eql("INVALID_MFA_CODE"));')))
    R.append(request(F6, "Verify with the right code", "verifyLogin", body={"challengeId": "{{challengeId}}", "code": "{{otpCode}}"}, auth="none",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("tokens issued", () => { pm.expect(body.accessToken).to.be.a("string"); pm.expect(body.refreshToken).to.be.a("string"); });',
                 SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F6, "Reuse the same code (expect 401)", "verifyLogin", body={"challengeId": "{{challengeId}}", "code": "{{otpCode}}"}, auth="none",
        note="**What this checks:** a code works only once.",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F6, "Turn off two-step with a wrong password (expect 401)", "disableMfa", body={"password": "Wrong1234"},
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F6, "Turn off two-step verification", "disableMfa", body={"password": "{{password}}"},
        tests=js(STATUS(204, "No Content"))))
    R.append(request(F6, "Log in - single step again", "login", body=LOGIN_BODY, auth="none",
        tests=js(STATUS(200, "OK"), 'pm.test("tokens returned directly", () => pm.expect(pm.response.json().accessToken).to.be.a("string"));')))

    F7 = "7. Internal APIs (developers only)"
    key = [("X-Internal-Api-Key", "{{internalApiKey}}")]
    R.append(request(F7, "Create credentials directly (auth-service)", "createCredential", url="{{authServiceUrl}}/internal/credentials",
        auth="none", headers=key,
        body={"customerId": "{{internalCustomerId}}", "email": "{{internalEmail}}", "password": "Sup3rSecret"},
        note="**Needs `internalApiKey`:** set it in the environment to `INTERNAL_API_KEY` from your `.env`. Calls auth-service directly on port 8081, not the gateway.",
        pre=js('pm.test("internalApiKey is set (copy INTERNAL_API_KEY from .env into the environment)", () => pm.expect(pm.variables.get("internalApiKey")).to.be.a("string").and.not.empty);',
               'pm.collectionVariables.set("internalCustomerId", pm.variables.replaceIn("{{$guid}}"));',
               'pm.collectionVariables.set("internalEmail", "internal." + Date.now() + "@example.com");'),
        tests=js(STATUS(201, "Created"))))
    R.append(request(F7, "Repeat the same call (idempotent retry, expect 200)", "createCredential", url="{{authServiceUrl}}/internal/credentials",
        auth="none", headers=key,
        body={"customerId": "{{internalCustomerId}}", "email": "{{internalEmail}}", "password": "Sup3rSecret"},
        note="**What this checks:** retrying the identical request is safe and creates no duplicate.",
        tests=js(STATUS(200, "OK"))))
    R.append(request(F7, "Create credentials without the key (expect 401)", "createCredential", url="{{authServiceUrl}}/internal/credentials",
        auth="none", body={"customerId": "{{internalCustomerId}}", "email": "{{internalEmail}}", "password": "Sup3rSecret"},
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F7, "Send an email directly (notification-service)", "sendEmail", url="{{notificationServiceUrl}}/internal/notifications/email",
        auth="none", headers=key,
        body={"type": "LOGIN_OTP", "recipient": "test@example.com", "variables": {"code": "123456", "expiresInMinutes": "5"}},
        note="Calls notification-service directly on port 8086. Afterwards read the email in Mailpit (http://localhost:8025).",
        tests=js(STATUS(201, "Created"), 'pm.test("delivery recorded as SENT", () => pm.expect(pm.response.json().status).to.eql("SENT"));')))
    R.append(request(F7, "Send an email with a missing variable (expect 400)", "sendEmail", url="{{notificationServiceUrl}}/internal/notifications/email",
        auth="none", headers=key, body={"type": "LOGIN_OTP", "recipient": "test@example.com", "variables": {}},
        tests=js(STATUS(400, "Bad Request"), 'pm.test("error is INVALID_NOTIFICATION", () => pm.expect(pm.response.json().error).to.eql("INVALID_NOTIFICATION"));')))
    F8 = "8. Identity verification (KYC)"
    R.append(request(F8, "Log in (fresh token for this folder)", "login", body=LOGIN_BODY, auth="none",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 SAVE("accessToken", "body.accessToken"), SAVE("refreshToken", "body.refreshToken"))))
    R.append(request(F8, "See my verification status", "getKycStatus",
        note="A new customer is level 0 with nothing started, and may not move money yet.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("is my status, level 0", () => { pm.expect(body.customerId).to.eql(pm.collectionVariables.get("customerId")); pm.expect(body.level).to.eql(0); });',
                 'pm.test("both tiers NOT_STARTED", () => body.tiers.forEach(t => pm.expect(t.status).to.eql("NOT_STARTED")));',
                 'pm.test("no operations allowed", () => pm.expect(body.capabilities.allowedOperations).to.eql([]));')))
    R.append(request(F8, "See my status without a token (expect 401)", "getKycStatus", auth="none",
        note="**What this checks:** the gateway refuses requests with no access token.",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F8, "Submit tier 2 before tier 1 (expect 422)", "submitTierTwo",
        body={"idType": "NIN", "idNumber": "12345678901"},
        note="**What this checks:** tier 2 needs a verified tier 1 first.",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is KYC_TIER_PREREQUISITE", () => pm.expect(pm.response.json().error).to.eql("KYC_TIER_PREREQUISITE"));')))
    R.append(request(F8, "Submit tier 1 as someone under 18 (expect 422)", "submitTierOne",
        body={"dateOfBirth": "2020-01-01", "addressLine1": "12 Marina Road", "city": "Lagos", "state": "Lagos", "postalCode": "101233", "country": "NG"},
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is KYC_NOT_ELIGIBLE", () => pm.expect(pm.response.json().error).to.eql("KYC_NOT_ELIGIBLE"));')))
    R.append(request(F8, "Submit tier 1", "submitTierOne",
        body={"dateOfBirth": "1990-05-14", "addressLine1": "12 Marina Road", "city": "Lagos", "state": "Lagos", "postalCode": "101233", "country": "NG"},
        note="Recorded as `PENDING` and waits for a person to review it. The submission id is saved as `submissionId`.",
        tests=js(STATUS(201, "Created"), "const body = pm.response.json();",
                 'pm.test("recorded as PENDING", () => pm.expect(body.status).to.eql("PENDING"));',
                 SAVE("submissionId", "body.id"))))
    R.append(request(F8, "Submit tier 1 again (expect 409)", "submitTierOne",
        body={"dateOfBirth": "1990-05-14", "addressLine1": "12 Marina Road", "city": "Lagos", "state": "Lagos", "postalCode": "101233", "country": "NG"},
        note="**What this checks:** only one open submission per tier.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is KYC_ALREADY_SUBMITTED", () => pm.expect(pm.response.json().error).to.eql("KYC_ALREADY_SUBMITTED"));')))
    R.append(request(F8, "Status while waiting for review", "getKycStatus",
        note="Still level 0: a pending tier grants nothing.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("tier 1 PENDING, level still 0", () => { pm.expect(body.tiers[0].status).to.eql("PENDING"); pm.expect(body.level).to.eql(0); });')))

    F9 = "9. KYC review and enforcement (developers only)"
    key = [("X-Internal-Api-Key", "{{internalApiKey}}")]
    reviewer = key + [("X-Reviewer", "reviewer.jane")]
    R.append(request(F9, "Capabilities at level 0", "getCustomerCapabilities",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/capabilities", auth="none", headers=key,
        note="**Needs `internalApiKey`.** Calls kyc-service directly on port 8087, not the gateway. Run folder 8 first.",
        tests=js(STATUS(200, "OK"), 'pm.test("level 0, nothing allowed", () => { const b = pm.response.json(); pm.expect(b.level).to.eql(0); pm.expect(b.allowedOperations).to.eql([]); });')))
    R.append(request(F9, "Capabilities without the key (expect 401)", "getCustomerCapabilities",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/capabilities", auth="none",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F9, "Authorize a transfer at level 0 (expect refused)", "authorizeOperation",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/authorize", auth="none", headers=key,
        body={"operation": "P2P_TRANSFER", "amount": "1000.00", "currency": "NGN"},
        note="**What this checks:** the enforcement point. Wallet and transfer services call this before moving money, so the rule holds for a direct API call too.",
        tests=js(STATUS(200, "OK"), 'pm.test("refused: level too low", () => { const b = pm.response.json(); pm.expect(b.allowed).to.eql(false); pm.expect(b.reasonCode).to.eql("KYC_LEVEL_TOO_LOW"); pm.expect(b.requiredLevel).to.eql(1); });')))
    R.append(request(F9, "List submissions waiting for review", "listReviewQueue",
        url="{{kycServiceUrl}}/internal/kyc/submissions", auth="none", headers=key,
        tests=js(STATUS(200, "OK"), 'pm.test("my submission is in the queue", () => pm.expect(pm.response.json().map(s => s.id)).to.include(pm.collectionVariables.get("submissionId")));')))
    R.append(request(F9, "Open the submission as a reviewer", "getSubmissionForReview",
        url="{{kycServiceUrl}}/internal/kyc/submissions/{{submissionId}}", auth="none", headers=reviewer,
        note="Every open is written to the audit trail with the reviewer's name.",
        tests=js(STATUS(200, "OK"), 'pm.test("shows the submission and its history", () => { const b = pm.response.json(); pm.expect(b.id).to.eql(pm.collectionVariables.get("submissionId")); pm.expect(b.history).to.be.an("array"); });')))
    R.append(request(F9, "Reject without a reason (expect 400)", "decideSubmission",
        url="{{kycServiceUrl}}/internal/kyc/submissions/{{submissionId}}/decision", auth="none", headers=reviewer,
        body={"decision": "REJECT"},
        tests=js(STATUS(400, "Bad Request"), 'pm.test("error is VALIDATION_ERROR", () => pm.expect(pm.response.json().error).to.eql("VALIDATION_ERROR"));')))
    R.append(request(F9, "Approve tier 1", "decideSubmission",
        url="{{kycServiceUrl}}/internal/kyc/submissions/{{submissionId}}/decision", auth="none", headers=reviewer,
        body={"decision": "APPROVE"},
        tests=js(STATUS(200, "OK"), 'pm.test("verified, customer is level 1", () => { const b = pm.response.json(); pm.expect(b.status).to.eql("VERIFIED"); pm.expect(b.customerLevel).to.eql(1); });')))
    R.append(request(F9, "Approve the same submission again (expect 409)", "decideSubmission",
        url="{{kycServiceUrl}}/internal/kyc/submissions/{{submissionId}}/decision", auth="none", headers=reviewer,
        body={"decision": "APPROVE"},
        note="**What this checks:** a decision cannot be repeated.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is KYC_NOT_REVIEWABLE", () => pm.expect(pm.response.json().error).to.eql("KYC_NOT_REVIEWABLE"));')))
    R.append(request(F9, "Authorize a transfer at level 1 (expect allowed)", "authorizeOperation",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/authorize", auth="none", headers=key,
        body={"operation": "P2P_TRANSFER", "amount": "1000.00", "currency": "NGN"},
        tests=js(STATUS(200, "OK"), 'pm.test("allowed", () => pm.expect(pm.response.json().allowed).to.eql(true));')))
    R.append(request(F9, "Authorize an external transfer at level 1 (expect refused)", "authorizeOperation",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/authorize", auth="none", headers=key,
        body={"operation": "EXTERNAL_TRANSFER", "amount": "1000.00", "currency": "NGN"},
        note="**What this checks:** external transfers need level 2.",
        tests=js(STATUS(200, "OK"), 'pm.test("refused: needs level 2", () => { const b = pm.response.json(); pm.expect(b.allowed).to.eql(false); pm.expect(b.requiredLevel).to.eql(2); });')))
    R.append(request(F9, "Authorize above the level 1 limit (expect refused)", "authorizeOperation",
        url="{{kycServiceUrl}}/internal/kyc/customers/{{customerId}}/authorize", auth="none", headers=key,
        body={"operation": "P2P_TRANSFER", "amount": "50000.01", "currency": "NGN"},
        note="**What this checks:** level 1 is capped at 50,000.00 NGN per transaction.",
        tests=js(STATUS(200, "OK"), 'pm.test("refused: over the limit", () => { const b = pm.response.json(); pm.expect(b.allowed).to.eql(false); pm.expect(b.reasonCode).to.eql("KYC_LIMIT_EXCEEDED"); });')))
    R.append(request(F9, "My status now shows level 1 (through the gateway)", "getKycStatus",
        tests=js(STATUS(200, "OK"), 'pm.test("level 1, tier 1 VERIFIED", () => { const b = pm.response.json(); pm.expect(b.level).to.eql(1); pm.expect(b.tiers[0].status).to.eql("VERIFIED"); });')))
    F10 = "10. Wallets"
    R.append(request(F10, "Open a wallet", "createWallet", body={"currency": "NGN"},
        note="Needs KYC level 1: run folders 8 and 9 first (they verify this customer). The wallet id is saved as `walletId`.",
        tests=js(STATUS(201, "Created"), "const body = pm.response.json();",
                 'pm.test("ACTIVE with zero balances", () => { pm.expect(body.status).to.eql("ACTIVE"); pm.expect(body.ledgerBalance).to.eql(0); pm.expect(body.availableBalance).to.eql(0); pm.expect(body.currency).to.eql("NGN"); });',
                 'pm.test("belongs to me", () => pm.expect(body.customerId).to.eql(pm.collectionVariables.get("customerId")));',
                 'pm.test("Location points at the new wallet", () => pm.expect(pm.response.headers.get("Location")).to.include(body.id));',
                 SAVE("walletId", "body.id"))))
    R.append(request(F10, "Open a second NGN wallet (expect 409)", "createWallet", body={"currency": "NGN"},
        note="**What this checks:** one live wallet per currency.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is WALLET_ALREADY_EXISTS", () => pm.expect(pm.response.json().error).to.eql("WALLET_ALREADY_EXISTS"));')))
    R.append(request(F10, "Open a USD wallet (expect 422)", "createWallet", body={"currency": "USD"},
        note="**What this checks:** unsupported currencies are refused (only NGN is enabled).",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is UNSUPPORTED_CURRENCY", () => pm.expect(pm.response.json().error).to.eql("UNSUPPORTED_CURRENCY"));')))
    R.append(request(F10, "Open a wallet with a malformed currency (expect 400)", "createWallet", body={"currency": "ngn"},
        tests=js(STATUS(400, "Bad Request"), 'pm.test("error is VALIDATION_ERROR", () => pm.expect(pm.response.json().error).to.eql("VALIDATION_ERROR"));')))
    R.append(request(F10, "Open a wallet without a token (expect 401)", "createWallet", body={"currency": "NGN"}, auth="none",
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F10, "List my wallets", "listMyWallets",
        tests=js(STATUS(200, "OK"), 'pm.test("my wallet is listed", () => pm.expect(pm.response.json().map(w => w.id)).to.include(pm.collectionVariables.get("walletId")));')))
    R.append(request(F10, "View my wallet", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        tests=js(STATUS(200, "OK"), 'pm.test("is my wallet", () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("walletId")));')))
    R.append(request(F10, "View someone else's wallet (expect 404)", "viewWallet",
        url="{{baseUrl}}/api/v1/wallets/00000000-0000-0000-0000-000000000001",
        note="**What this checks:** you cannot read another customer's wallet, and the answer does not reveal whether it exists.",
        tests=js(STATUS(404, "Not Found"), 'pm.test("error is WALLET_NOT_FOUND", () => pm.expect(pm.response.json().error).to.eql("WALLET_NOT_FOUND"));')))

    F11 = "11. Wallet rules (developers only)"
    key = [("X-Internal-Api-Key", "{{internalApiKey}}")]
    ops = key + [("X-Actor", "ops.jane")]
    W = "{{walletServiceUrl}}/internal/wallets/{{walletId}}"
    R.append(request(F11, "Put 5,000.00 into the wallet", "creditWallet", url=W + "/credit", auth="none", headers=key,
        body={"reference": "{{fundRef}}", "amount": 5000.00, "currency": "NGN"},
        note="**Needs `internalApiKey`.** Calls wallet-service directly on port 8083. Run folder 10 first. Every movement has a unique `reference`.",
        pre=js('pm.collectionVariables.set("fundRef", "fund-" + Date.now());'),
        tests=js(STATUS(201, "Created"), 'pm.test("balance is 5000.00", () => { const b = pm.response.json(); pm.expect(b.ledgerBalanceAfter).to.eql(5000); pm.expect(b.availableBalanceAfter).to.eql(5000); });')))
    R.append(request(F11, "Send the same request again (expect 200, nothing added)", "creditWallet", url=W + "/credit", auth="none", headers=key,
        body={"reference": "{{fundRef}}", "amount": 5000.00, "currency": "NGN"},
        note="**What this checks:** a retry with the same reference returns the original result and moves no money twice.",
        tests=js(STATUS(200, "OK"), 'pm.test("balance is still 5000.00", () => pm.expect(pm.response.json().ledgerBalanceAfter).to.eql(5000));')))
    R.append(request(F11, "Reuse the reference for a different amount (expect 409)", "creditWallet", url=W + "/credit", auth="none", headers=key,
        body={"reference": "{{fundRef}}", "amount": 6000.00, "currency": "NGN"},
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is REFERENCE_REUSED", () => pm.expect(pm.response.json().error).to.eql("REFERENCE_REUSED"));')))
    R.append(request(F11, "Spend 2,000.00", "debitWallet", url=W + "/debit", auth="none", headers=key,
        body={"reference": "send-{{fundRef}}", "amount": 2000.00, "currency": "NGN"},
        tests=js(STATUS(201, "Created"), 'pm.test("balance is 3000.00", () => pm.expect(pm.response.json().availableBalanceAfter).to.eql(3000));')))
    R.append(request(F11, "Spend more than is available (expect 422)", "debitWallet", url=W + "/debit", auth="none", headers=key,
        body={"reference": "toomuch-{{fundRef}}", "amount": 999999.00, "currency": "NGN"},
        note="**What this checks:** a wallet can never go below zero.",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is INSUFFICIENT_FUNDS", () => pm.expect(pm.response.json().error).to.eql("INSUFFICIENT_FUNDS"));')))
    R.append(request(F11, "Spend in the wrong currency (expect 422)", "debitWallet", url=W + "/debit", auth="none", headers=key,
        body={"reference": "usd-{{fundRef}}", "amount": 1.00, "currency": "USD"},
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is CURRENCY_MISMATCH", () => pm.expect(pm.response.json().error).to.eql("CURRENCY_MISMATCH"));')))
    R.append(request(F11, "Freeze the wallet", "changeWalletStatus", url=W + "/status", auth="none", headers=ops,
        body={"status": "FROZEN", "reason": "Postman test"},
        note="A frozen wallet cannot send money but can still receive it.",
        tests=js(STATUS(200, "OK"), 'pm.test("now FROZEN", () => pm.expect(pm.response.json().wallet.status).to.eql("FROZEN"));')))
    R.append(request(F11, "Spend from the frozen wallet (expect 422)", "debitWallet", url=W + "/debit", auth="none", headers=key,
        body={"reference": "frozen-{{fundRef}}", "amount": 10.00, "currency": "NGN"},
        note="**What this checks:** the wallet-status rule for outgoing money.",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is WALLET_NOT_ACTIVE", () => pm.expect(pm.response.json().error).to.eql("WALLET_NOT_ACTIVE"));')))
    R.append(request(F11, "Receive 500.00 into the frozen wallet", "creditWallet", url=W + "/credit", auth="none", headers=key,
        body={"reference": "frozen-in-{{fundRef}}", "amount": 500.00, "currency": "NGN"},
        tests=js(STATUS(201, "Created"), 'pm.test("balance is 3500.00", () => pm.expect(pm.response.json().ledgerBalanceAfter).to.eql(3500));')))
    R.append(request(F11, "Block the wallet", "changeWalletStatus", url=W + "/status", auth="none", headers=ops,
        body={"status": "BLOCKED", "reason": "Postman test"},
        tests=js(STATUS(200, "OK"), 'pm.test("now BLOCKED", () => pm.expect(pm.response.json().wallet.status).to.eql("BLOCKED"));')))
    R.append(request(F11, "Send money to the blocked wallet (expect 422)", "creditWallet", url=W + "/credit", auth="none", headers=key,
        body={"reference": "blocked-{{fundRef}}", "amount": 10.00, "currency": "NGN"},
        note="**What this checks:** a blocked wallet can neither send nor receive.",
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is WALLET_NOT_ACCEPTING_FUNDS", () => pm.expect(pm.response.json().error).to.eql("WALLET_NOT_ACCEPTING_FUNDS"));')))
    R.append(request(F11, "Close a wallet that holds money (expect 422)", "changeWalletStatus", url=W + "/status", auth="none", headers=ops,
        body={"status": "CLOSED", "reason": "Postman test"},
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is WALLET_NOT_EMPTY", () => pm.expect(pm.response.json().error).to.eql("WALLET_NOT_EMPTY"));')))
    R.append(request(F11, "Change status without saying who (expect 400)", "changeWalletStatus", url=W + "/status", auth="none", headers=key,
        body={"status": "ACTIVE", "reason": "Postman test"},
        tests=js(STATUS(400, "Bad Request"))))
    R.append(request(F11, "Reactivate the wallet", "changeWalletStatus", url=W + "/status", auth="none", headers=ops,
        body={"status": "ACTIVE", "reason": "Postman test"},
        tests=js(STATUS(200, "OK"), 'pm.test("ACTIVE again, with the full history", () => { const b = pm.response.json(); pm.expect(b.wallet.status).to.eql("ACTIVE"); pm.expect(b.statusHistory.length).to.eql(3); pm.expect(b.statusHistory[0].actor).to.eql("ops.jane"); });')))
    R.append(request(F11, "Change status without the key (expect 401)", "changeWalletStatus", url=W + "/status", auth="none",
        body={"status": "FROZEN", "reason": "Postman test"}, headers=[("X-Actor", "ops.jane")],
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F11, "My wallet now shows 3,500.00 (through the gateway)", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        tests=js(STATUS(200, "OK"), 'pm.test("ACTIVE, 3500.00 ledger and available", () => { const b = pm.response.json(); pm.expect(b.status).to.eql("ACTIVE"); pm.expect(b.ledgerBalance).to.eql(3500); pm.expect(b.availableBalance).to.eql(3500); });')))
    F12 = "12. Wallet funding"
    R.append(request(F12, "Note my wallet balance before funding", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        note="Needs a verified customer with a wallet: run folders 8, 9 and 10 first. Saves the balance as `balanceBefore`.",
        tests=js(STATUS(200, "OK"), SAVE("balanceBefore", "pm.response.json().ledgerBalance"))))
    R.append(request(F12, "Add 2,500.00 to my wallet", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 2500.00, "currency": "NGN", "source": "BANK_TRANSFER"},
        note="Recorded as `PENDING` with a unique reference. **No money is added yet**: the wallet is credited only after the payment is confirmed.",
        tests=js(STATUS(201, "Created"), "const body = pm.response.json();",
                 'pm.test("PENDING with a unique reference", () => { pm.expect(body.status).to.eql("PENDING"); pm.expect(body.reference).to.match(/^FND-[0-9A-F]{20}$/); });',
                 'pm.test("Location points at the funding", () => pm.expect(pm.response.headers.get("Location")).to.include(body.reference));',
                 SAVE("fundingRef", "body.reference"))))
    R.append(request(F12, "My balance has not changed yet", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        note="**What this checks:** starting a funding does not touch the balance.",
        tests=js(STATUS(200, "OK"), 'pm.test("balance unchanged", () => pm.expect(pm.response.json().ledgerBalance).to.eql(pm.collectionVariables.get("balanceBefore")));')))
    R.append(request(F12, "See the funding", "viewFunding", url="{{baseUrl}}/api/v1/funding/{{fundingRef}}",
        tests=js(STATUS(200, "OK"), 'pm.test("still PENDING", () => pm.expect(pm.response.json().status).to.eql("PENDING"));')))
    R.append(request(F12, "List my fundings", "listMyFundings",
        tests=js(STATUS(200, "OK"), 'pm.test("my funding is listed", () => pm.expect(pm.response.json().map(f => f.reference)).to.include(pm.collectionVariables.get("fundingRef")));')))
    R.append(request(F12, "Add more than my level allows (expect 403)", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 50000.01, "currency": "NGN", "source": "CARD"},
        note="**What this checks:** the KYC level's per-transaction limit (50,000.00 NGN at level 1) is enforced, live.",
        tests=js(STATUS(403, "Forbidden"), 'pm.test("error is KYC_LIMIT_EXCEEDED", () => pm.expect(pm.response.json().error).to.eql("KYC_LIMIT_EXCEEDED"));')))
    R.append(request(F12, "Fund in the wrong currency (expect 422)", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 10.00, "currency": "USD", "source": "CARD"},
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is CURRENCY_MISMATCH", () => pm.expect(pm.response.json().error).to.eql("CURRENCY_MISMATCH"));')))
    R.append(request(F12, "Fund someone else's wallet (expect 404)", "startFunding",
        body={"walletId": "00000000-0000-0000-0000-000000000001", "amount": 10.00, "currency": "NGN", "source": "CARD"},
        tests=js(STATUS(404, "Not Found"), 'pm.test("error is WALLET_NOT_FOUND", () => pm.expect(pm.response.json().error).to.eql("WALLET_NOT_FOUND"));')))
    R.append(request(F12, "Fund with an invalid amount (expect 400)", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 0, "currency": "NGN", "source": "CARD"},
        tests=js(STATUS(400, "Bad Request"), 'pm.test("error is VALIDATION_ERROR", () => pm.expect(pm.response.json().error).to.eql("VALIDATION_ERROR"));')))
    R.append(request(F12, "Fund without a token (expect 401)", "startFunding", auth="none",
        body={"walletId": "{{walletId}}", "amount": 10.00, "currency": "NGN", "source": "CARD"},
        tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F12, "See someone else's funding (expect 404)", "viewFunding", url="{{baseUrl}}/api/v1/funding/FND-00000000000000000000",
        tests=js(STATUS(404, "Not Found"), 'pm.test("error is FUNDING_NOT_FOUND", () => pm.expect(pm.response.json().error).to.eql("FUNDING_NOT_FOUND"));')))

    F13 = "13. Funding confirmation (developers only)"
    key = [("X-Internal-Api-Key", "{{internalApiKey}}")]
    ops = key + [("X-Actor", "ops.jane")]
    CONFIRM = "{{transactionServiceUrl}}/internal/funding/"
    R.append(request(F13, "The provider confirms the payment", "confirmFunding", url=CONFIRM + "{{fundingRef}}/confirm", auth="none", headers=key,
        body={"outcome": "SUCCESS", "amount": 2500.00, "currency": "NGN", "providerReference": "PSK_POSTMAN"},
        note="**Needs `internalApiKey`.** Calls transaction-service directly on port 8084. This plays the payment provider's callback for the funding from folder 12.",
        tests=js(STATUS(200, "OK"), "const body = pm.response.json();",
                 'pm.test("SUCCESS after PENDING -> PROCESSING -> SUCCESS", () => { pm.expect(body.status).to.eql("SUCCESS"); pm.expect(body.history.map(h => h.to)).to.eql(["PENDING", "PROCESSING", "SUCCESS"]); });')))
    R.append(request(F13, "My wallet now holds the money", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        note="**What this checks:** the balance went up by exactly the funded amount.",
        tests=js(STATUS(200, "OK"), 'pm.test("balance is up by 2500.00 (ledger and available)", () => { const b = pm.response.json(); const expected = pm.collectionVariables.get("balanceBefore") + 2500; pm.expect(b.ledgerBalance).to.eql(expected); pm.expect(b.availableBalance).to.eql(expected); });')))
    R.append(request(F13, "The provider sends the same confirmation again", "confirmFunding", url=CONFIRM + "{{fundingRef}}/confirm", auth="none", headers=key,
        body={"outcome": "SUCCESS", "amount": 2500.00, "currency": "NGN", "providerReference": "PSK_POSTMAN"},
        note="**What this checks:** providers retry. A repeat returns the current state and credits nothing twice.",
        tests=js(STATUS(200, "OK"), 'pm.test("still SUCCESS", () => pm.expect(pm.response.json().status).to.eql("SUCCESS"));')))
    R.append(request(F13, "Balance is unchanged by the repeat", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        tests=js(STATUS(200, "OK"), 'pm.test("still up by 2500.00 only", () => pm.expect(pm.response.json().ledgerBalance).to.eql(pm.collectionVariables.get("balanceBefore") + 2500));')))
    R.append(request(F13, "Report failure for a funding that succeeded (expect 409)", "confirmFunding", url=CONFIRM + "{{fundingRef}}/confirm", auth="none", headers=key,
        body={"outcome": "FAILED", "failureReason": "contradiction"},
        note="**What this checks:** a SUCCESS funding can never become FAILED.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is FUNDING_STATE_CONFLICT", () => pm.expect(pm.response.json().error).to.eql("FUNDING_STATE_CONFLICT"));')))
    R.append(request(F13, "Start a second funding (1,000.00)", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 1000.00, "currency": "NGN", "source": "CARD"},
        tests=js(STATUS(201, "Created"), SAVE("failedRef", "pm.response.json().reference"))))
    R.append(request(F13, "The payment fails", "confirmFunding", url=CONFIRM + "{{failedRef}}/confirm", auth="none", headers=key,
        body={"outcome": "FAILED", "failureReason": "Card declined"},
        tests=js(STATUS(200, "OK"), 'pm.test("FAILED with the reason", () => { const b = pm.response.json(); pm.expect(b.status).to.eql("FAILED"); pm.expect(b.failureReason).to.eql("Card declined"); });')))
    R.append(request(F13, "Report success for the failed funding (expect 409)", "confirmFunding", url=CONFIRM + "{{failedRef}}/confirm", auth="none", headers=key,
        body={"outcome": "SUCCESS", "amount": 1000.00, "currency": "NGN"},
        note="**What this checks:** a FAILED funding can never become SUCCESS, so a failure never adds spendable balance.",
        tests=js(STATUS(409, "Conflict"), 'pm.test("error is FUNDING_ALREADY_FAILED", () => pm.expect(pm.response.json().error).to.eql("FUNDING_ALREADY_FAILED"));')))
    R.append(request(F13, "Balance is still up by 2,500.00 only", "viewWallet", url="{{baseUrl}}/api/v1/wallets/{{walletId}}",
        tests=js(STATUS(200, "OK"), 'pm.test("the failed funding added nothing", () => pm.expect(pm.response.json().availableBalance).to.eql(pm.collectionVariables.get("balanceBefore") + 2500));')))
    R.append(request(F13, "Start a third funding (900.00)", "startFunding",
        body={"walletId": "{{walletId}}", "amount": 900.00, "currency": "NGN", "source": "EXTERNAL_PROVIDER"},
        tests=js(STATUS(201, "Created"), SAVE("reviewRef", "pm.response.json().reference"))))
    R.append(request(F13, "The provider reports a different amount", "confirmFunding", url=CONFIRM + "{{reviewRef}}/confirm", auth="none", headers=key,
        body={"outcome": "SUCCESS", "amount": 800.00, "currency": "NGN"},
        note="**What this checks:** an amount nobody asked for is never credited. It waits for a person.",
        tests=js(STATUS(200, "OK"), 'pm.test("held for review, not credited", () => { const b = pm.response.json(); pm.expect(b.status).to.eql("REQUIRES_REVIEW"); pm.expect(b.reviewCode).to.eql("AMOUNT_MISMATCH"); });')))
    R.append(request(F13, "Retry the mismatched funding (expect 422)", "retryFunding", url=CONFIRM + "{{reviewRef}}/retry", auth="none", headers=ops,
        tests=js(STATUS(422, "Unprocessable Entity"), 'pm.test("error is FUNDING_NOT_RETRYABLE", () => pm.expect(pm.response.json().error).to.eql("FUNDING_NOT_RETRYABLE"));')))
    R.append(request(F13, "Open the first funding with its history", "getFundingForOperations", url=CONFIRM + "{{fundingRef}}", auth="none", headers=key,
        tests=js(STATUS(200, "OK"), 'pm.test("full audit trail", () => { const b = pm.response.json(); pm.expect(b.history.length).to.eql(3); pm.expect(b.history.map(h => h.actor)).to.eql(["customer", "provider", "system"]); pm.expect(b.providerReference).to.eql("PSK_POSTMAN"); });')))
    R.append(request(F13, "Confirm without the key (expect 401)", "confirmFunding", url=CONFIRM + "{{fundingRef}}/confirm", auth="none",
        body={"outcome": "FAILED"}, tests=js(STATUS(401, "Unauthorized"))))
    R.append(request(F13, "Confirm an unknown reference (expect 404)", "confirmFunding", url=CONFIRM + "FND-00000000000000000000/confirm", auth="none", headers=key,
        body={"outcome": "FAILED"},
        tests=js(STATUS(404, "Not Found"), 'pm.test("error is FUNDING_NOT_FOUND", () => pm.expect(pm.response.json().error).to.eql("FUNDING_NOT_FOUND"));')))
    return R


def build_collection(ops):
    folders, order = {}, []
    for r in build_requests():
        if r["op"]:
            op = ops[r["op"]]
            method = r["method"] or op["method"]
            url = r["url"] or "{{baseUrl}}" + op["path"]
            description = (r["note"] + "\n\n---\n\n" if r["note"] else "") + f"**{op['summary']}**\n\n{op['description']}"
        else:
            method, url = r["method"], r["url"]
            description = r["description"]
        item = {"name": r["name"], "request": {"method": method, "header": [], "url": None, "description": description},
                "event": []}
        # Postman wants host as the first segment ({{baseUrl}}) and the remaining path parts
        segments = url.split("/")
        item["request"]["url"] = {"raw": url, "host": [segments[0]], "path": [s for s in segments[1:] if s]}
        for name, value in r["headers"]:
            item["request"]["header"].append({"key": name, "value": value, "type": "text"})
        if r["body"] is not None:
            item["request"]["header"].append({"key": "Content-Type", "value": "application/json", "type": "text"})
            item["request"]["body"] = {"mode": "raw", "raw": json.dumps(r["body"], indent=2),
                                       "options": {"raw": {"language": "json"}}}
        if r["auth"] == "none":
            item["request"]["auth"] = {"type": "noauth"}
        if r["pre"]:
            item["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": r["pre"]}})
        if r["tests"]:
            item["event"].append({"listen": "test", "script": {"type": "text/javascript", "exec": r["tests"]}})
        if r["folder"] not in folders:
            folders[r["folder"]] = []
            order.append(r["folder"])
        folders[r["folder"]].append(item)

    intro = {
        "0. Start here (health and inbox)": "Check the stack is up. Open Mailpit (http://localhost:8025) in a browser to watch the emails FlowPay sends.",
        "1. Register a customer": "Create an account. Public endpoint; limited to 5 requests per minute per IP.",
        "2. Log in": "Sign in and store the tokens. Wrong-password and unknown-email answers are identical on purpose.",
        "3. My profile": "View and maintain your own profile. Needs the bearer token (sent automatically).",
        "4. Sessions (refresh and log out)": "Token rotation, reuse detection, log out, log out everywhere.",
        "5. Password reset": "Forgot-password flow. Reads the emailed token from Mailpit automatically.",
        "6. Two-step verification (email code)": "Turn on the email code, log in with it, then turn it off. Reads the emailed codes from Mailpit automatically.",
        "7. Internal APIs (developers only)": "Service-to-service endpoints called directly on their own ports. Needs `internalApiKey` (INTERNAL_API_KEY from .env).",
        "8. Identity verification (KYC)": "Check your verification level and submit tier 1. Through the gateway with your bearer token.",
        "9. KYC review and enforcement (developers only)": "Approve the tier 1 submission as a reviewer, then see how the level changes what is allowed. Calls kyc-service directly; needs `internalApiKey`.",
        "10. Wallets": "Open a wallet once verified, list and view your wallets. Run after folders 8 and 9 (they verify this customer).",
        "12. Wallet funding": "Ask to add money to your wallet, follow it, and see the checks that refuse bad requests. Run after folders 8 to 10.",
        "13. Funding confirmation (developers only)": "Play the payment provider: confirm, repeat, fail and mismatch a payment, and watch the wallet balance. Calls transaction-service directly; needs `internalApiKey`.",
        "11. Wallet rules (developers only)": "Put money in and take it out, retries, overspending, and the freeze / block / close rules. Calls wallet-service directly; needs `internalApiKey`.",
    }
    collection = {
        "info": {
            "name": "FlowPay API",
            "description": "Runnable collection for the FlowPay API, served through the gateway at {{baseUrl}}.\n\n"
                           "## How to use\n"
                           "1. Start the stack: `docker compose up --build -d`.\n"
                           "2. Import this collection **and** `flowpay-local.postman_environment.json`, then select the *FlowPay - Local* environment.\n"
                           "3. Run the folders **in order** (right-click the collection -> Run), or click requests one by one. "
                           "Each request has tests; tokens, ids and emailed codes are captured automatically into collection variables.\n"
                           "4. For folders 7, 9, 11 and 13 set `internalApiKey` in the environment to `INTERNAL_API_KEY` from your `.env`.\n\n"
                           "## Good to know\n"
                           "* Rate limits apply (for example 5 registrations and 10 logins per minute per IP). If you get `429`, wait 60 seconds; "
                           "when running the whole collection, set a delay of about 1500 ms between requests.\n"
                           "* Each run registers a brand-new customer, so you can run it repeatedly.\n"
                           "* Emails go to Mailpit (http://localhost:8025), not to real inboxes.\n"
                           "* Every request's description explains how the endpoint works and how to test it.",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
        "variable": [
            {"key": "baseUrl", "value": "http://localhost:8080"},
            {"key": "mailpitUrl", "value": "http://localhost:8025"},
            {"key": "authServiceUrl", "value": "http://localhost:8081"},
            {"key": "notificationServiceUrl", "value": "http://localhost:8086"},
            {"key": "kycServiceUrl", "value": "http://localhost:8087"},
            {"key": "walletServiceUrl", "value": "http://localhost:8083"},
            {"key": "transactionServiceUrl", "value": "http://localhost:8084"},
            {"key": "password", "value": "Sup3rSecret"},
            {"key": "newPassword", "value": "Brand9NewPass"},
        ],
        "item": [{"name": f, "description": intro.get(f, ""), "item": folders[f]} for f in order],
    }
    return collection


def build_environment():
    return {
        "id": "flowpay-local",
        "name": "FlowPay - Local",
        "values": [
            {"key": "baseUrl", "value": "http://localhost:8080", "type": "default", "enabled": True},
            {"key": "mailpitUrl", "value": "http://localhost:8025", "type": "default", "enabled": True},
            {"key": "authServiceUrl", "value": "http://localhost:8081", "type": "default", "enabled": True},
            {"key": "notificationServiceUrl", "value": "http://localhost:8086", "type": "default", "enabled": True},
            {"key": "kycServiceUrl", "value": "http://localhost:8087", "type": "default", "enabled": True},
            {"key": "walletServiceUrl", "value": "http://localhost:8083", "type": "default", "enabled": True},
            {"key": "transactionServiceUrl", "value": "http://localhost:8084", "type": "default", "enabled": True},
            {"key": "internalApiKey", "value": "", "type": "secret", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


def main():
    user, auth, notification, kyc, wallet, transaction = (fetch(USER_URL), fetch(AUTH_URL), fetch(NOTIFICATION_URL),
                                                          fetch(KYC_URL), fetch(WALLET_URL), fetch(TRANSACTION_URL))
    public, internal = merge_specs(user, auth, notification, kyc, wallet, transaction)
    ops = operations(public, internal)

    dump(os.path.join(DOCS, "flowpay-openapi.json"), public)
    dump(os.path.join(DOCS, "flowpay-internal-openapi.json"), internal)
    dump(os.path.join(DOCS, "flowpay.postman_collection.json"), build_collection(ops))
    dump(os.path.join(DOCS, "flowpay-local.postman_environment.json"), build_environment())

    os.makedirs(GATEWAY_STATIC, exist_ok=True)
    for name in ("flowpay-openapi.json", "flowpay-internal-openapi.json"):
        shutil.copy(os.path.join(DOCS, name), os.path.join(GATEWAY_STATIC, name))

    count = sum(1 for item in public["paths"].values() for op in item.values() if isinstance(op, dict))
    internal_count = sum(1 for item in internal["paths"].values() for op in item.values() if isinstance(op, dict))
    print(f"public operations: {count}, internal operations: {internal_count}")
    print("wrote", DOCS)


if __name__ == "__main__":
    main()
