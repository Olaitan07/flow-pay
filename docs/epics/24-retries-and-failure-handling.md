# Epic 24 — Retries & Failure Handling

## Objective

Recover safely from transient failures without duplicating financial operations.  
Retry Classification  
Potentially retryable:  
Temporary network failure  
Temporary provider outage  
Temporary service unavailable  
Generally non-retryable without changing input/state:  
Invalid request  
Insufficient funds  
Authentication failure  
Unsupported currency  
Business-rule violation  

## Functional Requirements

Retries for financial operations must work with idempotency.  
Retry policies must define:  
Maximum attempts.  
Delay/backoff.  
Retryable conditions.  
Non-retryable conditions.  
Exhaustion behaviour.  

## Acceptance Criteria

Retries must not create duplicate debits, credits, payouts, ledger entries, or transactions.
