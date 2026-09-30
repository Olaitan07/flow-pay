# Epic 3 — Kyc / Identity Verification

## Objective

Verify customer identity and determine permitted financial capabilities.  

## User Story

As a customer,  
I want to complete identity verification,  
so that I can access financial features appropriate to my verification level.  
Possible Statuses  
NOT_STARTED  
PENDING  
VERIFIED  
REJECTED  
REQUIRES_REVIEW  

## Functional Requirements

The system must:  
Capture required verification information.  
Track verification status.  
Support different verification tiers.  
Apply functionality and limits based on KYC level.  
Record verification decisions.  
Restrict customers who do not satisfy required verification rules.  

## Acceptance Criteria

Given a customer requires KYC  
When valid verification information is submitted  
Then the verification request must be recorded.  
Given a customer's KYC level does not permit an operation  
When the operation is attempted  
Then it must be rejected regardless of whether the request originates from the UI or directly through an API.
