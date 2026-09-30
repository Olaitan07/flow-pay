# FlowPay — Glossary

Debit  
A financial entry representing value moving from the relevant account side according to the accounting model.  
Credit  
The corresponding financial entry on the other side of the accounting model.  
Ledger  
The authoritative accounting record containing financial entries.  
Double-Entry Accounting  
Every financial movement produces balanced debit and credit entries.  
Total Debit = Total Credit  
Available Balance  
Funds currently available to spend.  
Ledger Balance  
Balance according to recorded financial entries, depending on the platform's accounting model.  
Hold  
Funds reserved for an operation but not yet finally captured/settled.  
Idempotency  
Processing the same logical request repeatedly without creating repeated financial effects.  
Reversal  
A financial operation that offsets an earlier transaction.  
Refund  
Returning funds following an eligible previous payment.  
Settlement  
The process of completing the underlying movement of funds between financial parties.  
Reconciliation  
Comparing independent records to identify inconsistencies.  
KYC  
Know Your Customer — processes used to verify customer identity.  
AML  
Anti-Money Laundering — controls designed to identify and mitigate money-laundering activity.  
Transaction Limit  
Maximum permitted financial activity according to configured rules.  
Velocity Limit  
A restriction based on the frequency or aggregate activity occurring within a defined period.  
Beneficiary  
A saved recipient/payment destination.  
Idempotency Key  
An identifier used to recognize retries of the same logical operation.  
Transaction Reference  
Unique identifier associated with a financial transaction.  
Pending Transaction  
A transaction whose final outcome has not yet been determined.  
Compensating Transaction  
A new financial operation used to offset the effect of a previous operation.  
Eventual Consistency  
Different components may reach the same final state at different times.  
Saga  
A distributed business-process pattern where multiple steps are coordinated with defined failure/compensation behaviour.  
Transactional Outbox  
A reliability pattern used to coordinate persisted state changes with eventual event publication.  
Circuit Breaker  
A resilience mechanism that temporarily stops calls to an unhealthy dependency.  
Retry  
Repeating an operation after an eligible transient failure.  
Timeout  
The maximum period a caller waits for an operation/dependency.  
Dead-Letter Queue  
A destination for messages/events that could not be processed successfully after defined handling attempts.  
Correlation ID  
An identifier used to trace one logical request/process across multiple components.  
PII  
Personally Identifiable Information.  
RPO  
Recovery Point Objective — acceptable amount of data loss measured in time.  
RTO  
Recovery Time Objective — acceptable time to restore a service after disruption.
