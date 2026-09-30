# Epic 16 — Reconciliation

## Objective

Identify differences between FlowPay's records and external/internal financial records.  

## System Requirement

Reconciliation should compare relevant sources such as:  
Transactions  
      ↕  
Internal Ledger  
      ↕  
Wallet Records  
      ↕  
Payment Provider  
      ↕  
Bank / Settlement Records  

## Functional Requirements

The system should identify:  
Missing transactions.  
Amount mismatches.  
Currency mismatches.  
Status mismatches.  
Duplicate records.  
Missing provider records.  
Unexpected provider transactions.  

## Acceptance Criteria

Example:  
FlowPay:  
TX100 = ₦10,000 SUCCESS  

Provider:  
TX100 = ₦10,000 SUCCESS  

Result = MATCHED  
Another:  
FlowPay:  
TX200 = PENDING  

Provider:  
TX200 = SUCCESS  

Result = MISMATCH  
Mismatches must be recorded for investigation or automated resolution.
