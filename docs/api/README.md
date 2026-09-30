# FlowPay API documentation

Three ways to explore and test the API. They all come from the same source: the annotations in the code.

| I want to... | Use |
|---|---|
| Read and try endpoints in the browser | **Swagger UI**: http://localhost:8080/swagger-ui.html |
| Run a guided, self-checking test journey | **Postman collection** (below) |
| Generate clients / import into another tool | `flowpay-openapi.json` (OpenAPI 3.1) |

Start the stack first: `docker compose up --build -d` (see the main README for the one-time `.env` setup).

---

## 1. Swagger UI

Open http://localhost:8080/swagger-ui.html.

* Use the **"Select a definition"** dropdown (top right): *FlowPay API* (what clients use, through the gateway) or *Internal service APIs* (service-to-service, direct ports).
* Every endpoint explains **what it does, how it works and how to test it**, lists every response it can return, and has an example body. Click an endpoint to expand it.
* To call protected endpoints (padlock icon):
  1. Try **POST /api/v1/users** then **POST /api/v1/auth/login** (*Try it out* -> *Execute*).
  2. Copy `accessToken` from the login response.
  3. Click **Authorize**, paste the token (no `Bearer ` prefix), click *Authorize*. The padlocks close and your token is sent automatically (it is remembered across page reloads).
  4. Access tokens last 15 minutes. Renew with **POST /api/v1/auth/refresh**.

The emails the system sends (password reset links, verification codes) land in the local inbox: **http://localhost:8025** (Mailpit).

---

## 2. Postman

### Import
1. Postman -> **Import** -> drop in both files from this folder:
   * `flowpay.postman_collection.json`
   * `flowpay-local.postman_environment.json`
2. Top-right environment picker -> select **FlowPay - Local**.
3. *(Only for folder 7)* In the environment, set `internalApiKey` to the value of `INTERNAL_API_KEY` in your `.env`.

### Run it
* **Whole journey:** right-click the collection -> **Run collection**. Tick *Delay* and set about **1500 ms** between requests (see "Rate limits" below). About 50 requests, 1.5 minutes.
* **By hand:** open a folder and click the requests top to bottom. **Order matters**: later requests use values saved by earlier ones.

Each request has:
* a **description** with what it does, how it works and how to test it (open the request -> *Documentation*, or the right-hand panel);
* **tests** (the *Test Results* tab) that check status codes, error codes and the security behaviour;
* **automatic variable capture**: ids, tokens and even the codes from emails are saved for you, so nothing needs copy-pasting.

### What is in the collection

| Folder | What it demonstrates |
|---|---|
| 0. Start here | Health check; empty the inbox |
| 1. Register a customer | Success, duplicate (409), invalid data (400) |
| 2. Log in | Login, wrong password, unknown email (identical 401) |
| 3. My profile | View, update, other customer's profile (404), no token (401), locked fields (422) |
| 4. Sessions | Refresh, reuse detection, logout, logout-everywhere |
| 5. Password reset | Request, identical answer for unknown emails, token read from the email, confirm, single use, sign-out everywhere |
| 6. Two-step verification | Enable with an emailed code, two-step login, wrong/reused code, disable |
| 7. Internal APIs | Direct calls to auth-service / notification-service with the internal key |

### Variables (set automatically)
`email`, `phone`, `customerId`, `accessToken`, `refreshToken`, `challengeId`, `otpCode`, `resetToken` ... A fresh customer is registered every run, so you can re-run the collection as often as you like.

### Rate limits (why you may see 429)
The gateway limits, per IP address per minute: **5** registrations, **10** logins (login + verify share it), **5** password-reset calls, **10** MFA calls. Clicking around by hand is fine. When running the whole collection, use the 1500 ms delay; if you still see a `429`, wait 60 seconds and re-run.

### Things worth trying by hand
* **Account lockout:** in *2. Log in*, send **"Log in with the wrong password"** five times, then **"Log in"**. Still 401: the account is locked for 15 minutes. Run folder 5 (password reset) to unlock it.
* **Expired link / code:** wait past the expiry (30 min / 5 min) and retry.
* **Read the real email:** open http://localhost:8025 while running folders 5 and 6.

---

## 3. The OpenAPI files

| File | Contents |
|---|---|
| `flowpay-openapi.json` | The public API through the gateway (13 operations) |
| `flowpay-internal-openapi.json` | Internal endpoints (2 operations) on their own ports |

Import either file into Postman (*Import -> File*), Insomnia, or a code generator.

### Regenerating after you change an endpoint
Documentation lives in the controllers and DTOs (`@Operation`, `@ApiResponse`, `@Schema`). After changing them:

```sh
docker compose up --build -d user-service auth-service notification-service   # rebuild with new annotations
python3 scripts/build-api-docs.py                                             # rewrites everything in docs/api
docker compose up --build -d api-gateway                                      # so Swagger UI shows the new spec
```

The script reads each service's live `/v3/api-docs`, merges them, and rewrites the two OpenAPI files, the Postman collection/environment, and the copies served by the gateway.
The Postman *journey* (order, tests, variable capture) is defined in `scripts/build-api-docs.py`; edit `build_requests()` to add a request.

### Turning documentation off (production)
Set `DOCS_ENABLED=false` for the gateway and the services: Swagger UI and the spec endpoints disappear, and the gateway stops treating them as public.
