# Epic 29 — Financial Invariants

## Objective

Define rules that must remain true regardless of application flow.  
Mandatory Invariants  
Money always has a currency.  

A successful financial movement has corresponding accounting records.  

Total ledger debits equal total ledger credits.  

A single idempotent operation cannot move money twice.  

A customer cannot spend more than permitted available funds.  

Posted ledger history cannot silently change.  

Every financial transaction has a unique reference.  

A reversal references its original transaction.  

Unauthorized users cannot access another customer's financial information.  

Financial state changes are auditable.  

## Acceptance Criteria

Any operation that would violate a financial invariant must not be committed as a valid financial state.  
Failures must be surfaced for investigation rather than silently ignored.
