# Epic 8 — Concurrency & Balance Protection

## Objective

Protect financial state when multiple operations occur simultaneously.  

## System Requirement

Consider:  
Available Balance = ₦100,000  

Transfer A = ₦80,000  
Transfer B = ₦70,000  
Both requests must not independently spend the same funds.  

## Functional Requirements

The platform must protect against:  
Lost updates.  
Double spending.  
Concurrent balance changes.  
Concurrent duplicate requests.  
Concurrent limit consumption.  

## Acceptance Criteria

Given the available balance is ₦100,000  
When two simultaneous transfers attempt to spend a combined ₦150,000  
Then successful transactions must not result in unauthorized overspending.
