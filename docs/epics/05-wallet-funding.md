# Epic 5 — Wallet Funding

## Objective

Allow customers to add money to wallets.  

## User Story

As a customer,  
I want to fund my wallet,  
so that I have funds available for transactions.  
Funding Sources  
The product may support:  
Bank transfer.  
Card.  
Internal FlowPay transfer.  
External payment provider.  

## Functional Requirements

A funding operation must have:  
Unique reference.  
Amount.  
Currency.  
Funding source.  
Status.  
Customer/wallet reference.  
Creation time.  

## Acceptance Criteria

Given wallet funding succeeds  
When confirmation is received  
Then the correct financial records must be created  
And the appropriate balance must reflect the funding.  
Given wallet funding fails  
Then the customer's spendable balance must not be incorrectly increased.
