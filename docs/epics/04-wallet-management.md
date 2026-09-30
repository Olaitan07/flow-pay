# Epic 4 — Wallet Management

## Objective

Allow customers to hold and manage funds.  

## User Story

As a verified customer,  
I want to have a wallet,  
so that I can store and transact funds.  
Wallet Information  
A wallet should contain:  
Wallet ID  
Customer ID  
Currency  
Ledger Balance  
Available Balance  
Status  
Created At  
Possible statuses:  
ACTIVE  
FROZEN  
BLOCKED  
CLOSED  

## Functional Requirements

The system must:  
Create supported wallets.  
Associate wallets with customers.  
Prevent unsupported currencies.  
Prevent unauthorized negative balances.  
Restrict transactions from inactive wallets.  

## Acceptance Criteria

Given a wallet is ACTIVE  
When an authorized operation occurs  
Then the operation may proceed subject to other business rules.  
Given a wallet is FROZEN, BLOCKED, or CLOSED  
When the customer attempts an outgoing transaction  
Then the transaction must be rejected according to wallet-status rules.
