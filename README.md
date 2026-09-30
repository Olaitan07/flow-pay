# FlowPay

FlowPay is a digital wallet and payments platform built as a set of microservices. Customers can register, verify their identity, hold wallets, fund them, and send money to other FlowPay users or external bank accounts, with financial correctness, security, and auditability as top priorities.

Implementation has not started yet. The project backend standard is Java 21+ with Spring Boot microservices, and Docker Compose is the standard for installing and running the local application environment and infrastructure.

## Project Structure

```
flowpay/
├── README.md
├── docs/
│   ├── product-overview.md
│   ├── product-requirements.md
│   ├── epics/
│   │   ├── 01-customer-management.md
│   │   ├── 02-authentication.md
│   │   ├── ...
│   │   └── 32-compliance-reporting-and-statements.md
│   └── glossary.md
└── services/
```

## Documentation

| Document | Contents |
|---|---|
| [Product Overview](docs/product-overview.md) | Problem being solved, goals, business domains, and development order |
| [Product Requirements](docs/product-requirements.md) | Financial principles, business rules, cross-cutting and non-functional requirements, definition of done |
| [Glossary](docs/glossary.md) | Fintech terms such as idempotency, ledger, settlement, and reconciliation |

### Epics

| # | Epic |
|---|---|
| 1 | [Customer Management](docs/epics/01-customer-management.md) |
| 2 | [Authentication](docs/epics/02-authentication.md) |
| 3 | [Kyc / Identity Verification](docs/epics/03-kyc-identity-verification.md) |
| 4 | [Wallet Management](docs/epics/04-wallet-management.md) |
| 5 | [Wallet Funding](docs/epics/05-wallet-funding.md) |
| 6 | [P2P Transfers](docs/epics/06-p2p-transfers.md) |
| 7 | [Idempotency](docs/epics/07-idempotency.md) |
| 8 | [Concurrency & Balance Protection](docs/epics/08-concurrency-and-balance-protection.md) |
| 9 | [Transaction Management](docs/epics/09-transaction-management.md) |
| 10 | [Ledger & Double-Entry Accounting](docs/epics/10-ledger-and-double-entry-accounting.md) |
| 11 | [Fees](docs/epics/11-fees.md) |
| 12 | [Transaction Limits](docs/epics/12-transaction-limits.md) |
| 13 | [Beneficiaries](docs/epics/13-beneficiaries.md) |
| 14 | [External Bank Transfers](docs/epics/14-external-bank-transfers.md) |
| 15 | [Reversals & Refunds](docs/epics/15-reversals-and-refunds.md) |
| 16 | [Reconciliation](docs/epics/16-reconciliation.md) |
| 17 | [Settlement](docs/epics/17-settlement.md) |
| 18 | [Notifications](docs/epics/18-notifications.md) |
| 19 | [Event Processing](docs/epics/19-event-processing.md) |
| 20 | [Caching](docs/epics/20-caching.md) |
| 21 | [Audit Trail](docs/epics/21-audit-trail.md) |
| 22 | [Fraud & Risk](docs/epics/22-fraud-and-risk.md) |
| 23 | [Rate Limiting](docs/epics/23-rate-limiting.md) |
| 24 | [Retries & Failure Handling](docs/epics/24-retries-and-failure-handling.md) |
| 25 | [Resilience](docs/epics/25-resilience.md) |
| 26 | [Security & Access Control](docs/epics/26-security-and-access-control.md) |
| 27 | [Observability](docs/epics/27-observability.md) |
| 28 | [Admin Operations](docs/epics/28-admin-operations.md) |
| 29 | [Financial Invariants](docs/epics/29-financial-invariants.md) |
| 30 | [Data Integrity & Data Management](docs/epics/30-data-integrity-and-data-management.md) |
| 31 | [Reliability, Backup & Disaster Recovery](docs/epics/31-reliability-backup-and-disaster-recovery.md) |
| 32 | [Compliance, Reporting & Statements](docs/epics/32-compliance-reporting-and-statements.md) |

## Local Development

Java 25, Spring Boot 4.0.8 and Maven multi-module (`services/*`). Services: `api-gateway` (8080), `auth-service` (8081), `user-service` (8082), `wallet-service` (8083), `transaction-service` (8084), `ledger-service` (8085), `notification-service` (8086). Each domain service owns its own PostgreSQL database (host ports 5433-5438) with a dedicated named volume; Redis and Kafka (KRaft) are shared infrastructure.

Prerequisites: Docker Desktop, and JDK 25 + Maven only if building outside Docker. On Apple Silicon use an ARM64 JDK, e.g. `export JAVA_HOME=~/jdk25-arm64/Contents/Home`.

```sh
cp .env.example .env            # set POSTGRES_PASSWORD
docker compose up --build -d    # build and run everything
docker compose logs -f
docker compose down             # keeps data
mvn -DskipTests package         # host build (JDK 25)
```

Run one service from the host against containerised infra: `docker compose up -d wallet-db redis`, then `SPRING_DATASOURCE_PASSWORD=<pw> java -jar services/wallet-service/target/wallet-service-*.jar`.

`docker compose down -v` deletes all volumes and their data; use it only for an intentional reset.
