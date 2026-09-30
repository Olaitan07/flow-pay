# Epic 28 — Admin Operations

## Objective

Allow authorized operational personnel to safely manage exceptional situations.  
Admin Capabilities  
Depending on role and policy:  
Search customer.  
View account status.  
Freeze wallet.  
Unfreeze wallet.  
Block account.  
Review transaction.  
Review KYC.  
Review risk case.  
Review reconciliation mismatch.  
Initiate permitted reversal.  
Manage configured limits.  

## Functional Requirements

Administrative permissions must use least privilege.  
High-risk actions may require:  
Additional authentication.  
Maker-checker approval.  
Reason/comment.  
Audit record.  

## Acceptance Criteria

An unauthorized admin role must not perform privileged financial operations.  
Every sensitive administrative action must be auditable.
