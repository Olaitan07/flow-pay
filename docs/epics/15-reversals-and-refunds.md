# Epic 15 — Reversals & Refunds

## Objective

Recover funds when eligible financial operations fail or must be undone.  

## User Story

As a customer,  
I want eligible failed transactions corrected,  
so that my funds are not permanently lost.  
Example  
₦20,000 debited  
      ↓  
External payment fails  
      ↓  
Reversal initiated  
      ↓  
₦20,000 restored  

## Functional Requirements

Reversals must reference original transactions.  
Original transactions must remain visible.  
Reversals must have unique references.  
A transaction must not be reversed more than permitted.  
Reversal ledger entries must balance.  

## Acceptance Criteria

Given an eligible transaction was debited but ultimately failed  
When reversal completes  
Then the appropriate funds must be restored  
And the original transaction history must remain intact  
And the reversal must be separately traceable.
