# Epic 11 — Fees

## Objective

Calculate and account for transaction fees.  

## User Story

As a customer,  
I want to see applicable fees before confirming a transaction,  
so that I understand the total charge.  

## Functional Requirements

Fees may depend on:  
Transaction type.  
Amount.  
Currency.  
Customer tier.  
Destination.  
Payment method.  
Completed transactions must retain the fee applied at execution time.  

## Acceptance Criteria

For:  
Transfer = ₦10,000  
Fee      = ₦100  
The customer should see:  
Amount      ₦10,000  
Fee            ₦100  
Total       ₦10,100  
After completion:  
Sender Debit       ₦10,100  
Recipient Credit   ₦10,000  
Fee Revenue           ₦100  
The ledger must remain balanced.
