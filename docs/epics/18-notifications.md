# Epic 18 — Notifications

## Objective

Inform customers about important account and transaction events.  

## User Story

As a customer,  
I want to receive transaction notifications,  
so that I know when activity occurs on my account.  
Events  
Notifications may include:  
Successful transfer.  
Failed transfer.  
Wallet funding.  
Reversal.  
Login/security event.  
KYC result.  
Account restriction.  

## Functional Requirements

Notification delivery failure must not reverse an otherwise successful financial transaction.  

## Acceptance Criteria

Given a transfer completes successfully  
When notification delivery fails  
Then the transfer must remain successful  
And notification processing may retry independently according to policy.
