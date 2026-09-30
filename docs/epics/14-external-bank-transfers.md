# Epic 14 — External Bank Transfers

## Objective

Allow customers to send funds to supported external financial institutions.  

## User Story

As a customer,  
I want to transfer funds to a bank account,  
so that I can pay recipients outside FlowPay.  
External Outcomes  
A provider may return:  
SUCCESS  
FAILED  
PENDING  
TIMEOUT  
UNKNOWN  

## Functional Requirements

The system must:  
Validate bank details.  
Validate account information where supported.  
Generate internal transaction references.  
Store provider references.  
Handle provider errors.  
Handle timeouts.  
Query uncertain transaction states where appropriate.  
Prevent duplicate external payouts.  

## Acceptance Criteria

A timeout must not automatically be treated as definitive failure when the external provider could still have processed the transaction.  
An uncertain transaction must enter an appropriate state until its outcome is determined.
