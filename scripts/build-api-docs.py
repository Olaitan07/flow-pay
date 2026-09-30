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
Override service URLs with USER_URL, AUTH_URL, NOTIFICATION_URL if you changed the ports.
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
(auth-service on port 8081, notification-service on port 8086) with the `X-Internal-Api-Key` header
(`INTERNAL_API_KEY` from your `.env`). Intended for development and debugging only.
"""


def merge_specs(user, auth, notification):
    public_paths, internal_paths = {}, {}
    schemas, tags = {}, {}

    for name, spec, base in (("user", user, USER_URL), ("auth", auth, AUTH_URL),
                             ("notification", notification, NOTIFICATION_URL)):
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
                           "4. For folder 7 set `internalApiKey` in the environment to `INTERNAL_API_KEY` from your `.env`.\n\n"
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
            {"key": "internalApiKey", "value": "", "type": "secret", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


def main():
    user, auth, notification = fetch(USER_URL), fetch(AUTH_URL), fetch(NOTIFICATION_URL)
    public, internal = merge_specs(user, auth, notification)
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
