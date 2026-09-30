# FlowPay — Product Requirements

Purpose: define the product, business, functional, financial, security, reliability, and operational requirements before choosing implementation technologies. User stories and acceptance criteria per feature live in [epics](epics/).

## 4. Core Financial Principles  
The following rules apply throughout FlowPay.  
Every monetary amount must have a currency.  
Financial operations must maintain monetary precision.  
Customers must not spend more than their available balance unless an explicit overdraft product exists.  
Every successful financial movement must be traceable.  
Every financial transaction must have a unique reference.  
Duplicate requests must not result in duplicate financial movements.  
Posted ledger entries must not be silently modified.  
Ledger debits and credits must balance.  
Financial operations must be auditable.  
Failed operations must not silently lose customer funds.  
Transaction state changes must follow defined transitions.  
Reversals must reference the original transaction.  
Authorization must be checked before financial operations.  
Financial decisions must not depend solely on stale cached data.  
Partial failures must have defined recovery behaviour.

## 33. AVAILABLE BALANCE VS LEDGER BALANCE  
FlowPay must distinguish these concepts where applicable.  
Example:  
Ledger Balance       ₦100,000  
Pending/Held Amount   ₦20,000  
Available Balance     ₦80,000  
Ledger Balance represents recorded funds according to the applicable accounting/balance model.  
Available Balance represents the amount currently available for spending.  
A customer must not be allowed to spend unavailable/held funds simply because the ledger balance is higher.

## 34. TRANSACTION STATE MANAGEMENT  
A possible transaction lifecycle:  
INITIATED  
     ↓  
PENDING  
     ↓  
PROCESSING  
    ↙       ↘  
SUCCESS    FAILED  
   ↓  
REVERSED  
Transitions must be explicitly defined.  
Not every transition is valid.  
For example, changing:  
FAILED → SUCCESS  
must not occur arbitrarily.  
Recovery or reprocessing behaviour must be explicitly defined and auditable.

## 35. DISTRIBUTED TRANSACTION REQUIREMENTS  
Microservices introduce situations where one business operation affects multiple systems.  
Example:  
Transfer  
   ↓  
Transaction recorded  
   ↓  
Wallet affected  
   ↓  
Ledger posted  
   ↓  
Notification requested  
The system must consider what happens if processing stops halfway.  
Product requirements must define whether an operation should:  
Retry  
Compensate  
Reverse  
Remain pending  
Require reconciliation  
Require manual review  
The technical implementation may later use patterns such as Saga or transactional outbox, but those technologies are implementation decisions rather than product requirements.

## 36. EVENTUAL CONSISTENCY  
Not every part of a distributed system will update simultaneously.  
Example:  
Transfer = SUCCESS  

0 ms     Transaction updated  
50 ms    Ledger event processed  
200 ms   Notification processed  
500 ms   Analytics updated  
This can be acceptable where the business permits eventual consistency.  
Financial requirements must explicitly identify which operations require immediate/strong consistency and which can tolerate delayed consistency.

## 37. TRANSACTIONAL OUTBOX REQUIREMENT  
The system must prevent situations where financial state changes successfully but an essential event that represents that change is permanently lost.  
Example risk:  
Database transaction commits  
       ↓  
Application attempts to publish event  
       ↓  
Application crashes  
       ↓  
Event never published  
The implementation must eventually provide a reliable mechanism for publishing required events associated with committed financial state.  
The specific technical pattern will be decided during architecture design.

## 38. SAGA / COMPENSATION REQUIREMENTS  
Long-running operations spanning multiple services cannot assume that every step succeeds.  
Example:  
Initiate transfer  
      ↓  
Reserve/debit funds  
      ↓  
External payout  
      ↓  
Ledger/financial completion  
If a later step fails, the business process must define an appropriate recovery action.  
Possible outcomes include:  
Retry  
Compensate  
Reverse  
Pending investigation  
Manual review  
The system must never invent compensation logic without a defined financial/business rule.

## 39. CURRENCY REQUIREMENTS  
Every monetary value must explicitly identify its currency.  
Examples:  
₦10,000 NGN  
$100 USD  
€100 EUR  
£100 GBP  
The platform must not assume that two numerical amounts are comparable when currencies differ.  
Cross-currency transactions require explicit conversion rules.

## 40. FOREIGN EXCHANGE REQUIREMENTS  
If FlowPay later supports currency conversion, the product should define:  
Source currency.  
Destination currency.  
Exchange rate.  
Rate provider/source.  
Rate timestamp.  
Rate validity period.  
Spread/markup.  
Conversion fee.  
Quote expiration.  
Example:  
Customer has:  
$100 USD  

Quote:  
1 USD = ₦1,500  

Converted amount:  
₦150,000  

Quote valid:  
30 seconds  
Expired quotes must not be silently reused when current pricing is required.

## 41. HOLDS / RESERVATIONS  
Some financial operations may require reserving funds before final completion.  
Example:  
Available Balance = ₦100,000  

Reserve = ₦30,000  

Ledger Balance = ₦100,000  
Available Balance = ₦70,000  
The customer must not spend the reserved ₦30,000 elsewhere.  
Holds must have defined:  
Creation.  
Expiration.  
Capture.  
Release.  
Cancellation.

## 42. FINTECH FAILURE SCENARIOS  
Every major financial feature should consider at least:  
Duplicate request  
Concurrent request  
Insufficient balance  
Invalid amount  
Zero amount  
Negative amount  
Unsupported currency  
Blocked wallet  
Suspended customer  
Expired authentication  
KYC restriction  
Transaction-limit exceeded  
Risk rejection  
Provider unavailable  
Provider timeout  
Internal timeout  
Database failure  
Event publishing failure  
Duplicate event  
Delayed event  
Out-of-order event  
Notification failure  
Reconciliation mismatch  
Settlement failure  
Reversal failure  
A feature should not be considered complete merely because its happy path works.

## 43. NON-FUNCTIONAL REQUIREMENTS  
FlowPay must eventually define measurable requirements for:  
Availability  
Critical financial services should have defined availability objectives.  
Performance  
Important operations should have measurable latency objectives.  
Scalability  
The system should support growth in:  
Customers  
Wallets  
Transactions  
Events  
Concurrent requests  
Transaction history  
Reliability  
Confirmed successful financial operations must not disappear.  
Durability  
Committed financial records must survive appropriate infrastructure failures.  
Security  
Sensitive customer and financial information must remain protected.  
Auditability  
Important financial actions must be reconstructable.  
Recoverability  
The platform must have documented and tested recovery processes.  
Maintainability  
Business domains should remain sufficiently separated to allow safe change.  
Observability  
Operational teams must be able to understand failures and transaction state.

## 45. SYSTEM-WIDE ACCEPTANCE RULES  
Every financial feature must answer:  
What happens when it succeeds?  

What happens when it fails?  

What happens when it times out?  

What happens when the customer retries?  

What happens when two requests arrive simultaneously?  

What happens when an event is delivered twice?  

What happens when an event arrives late?  

What happens when a dependency is unavailable?  

What happens when only half the workflow completes?  

How is money recovered?  

How is the operation reconciled?  

How can operations investigate it?  

How can we prove what happened later?

## 47. DEFINITION OF DONE FOR FINANCIAL FEATURES  
A financial feature is not complete merely because the happy path works.  
Before considering a financial feature complete, confirm:  
✓ Business requirements defined  

✓ User story defined where appropriate  

✓ Acceptance criteria defined  

✓ Authorization rules defined  

✓ Validation rules defined  

✓ Transaction states defined  

✓ Idempotency considered  

✓ Concurrency considered  

✓ Financial invariants preserved  

✓ Ledger impact defined  

✓ Fees considered  

✓ Limits considered  

✓ Failure scenarios defined  

✓ Timeout behaviour defined  

✓ Retry behaviour defined  

✓ Duplicate processing considered  

✓ Reversal/compensation defined  

✓ Audit requirements defined  

✓ Reconciliation impact considered  

✓ Security requirements considered  

✓ Sensitive-data handling defined  

✓ Observability requirements defined  

✓ Edge cases identified  

✓ Recovery behaviour defined
