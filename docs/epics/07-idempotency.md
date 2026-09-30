# Epic 7 — Idempotency

## Objective

Prevent duplicate financial operations.  

## System Requirement

The platform must safely handle clients retrying the same financial request.  
Example:  
Request 1  
Idempotency-Key: ABC123  
Transfer: ₦50,000  

Request 2  
Idempotency-Key: ABC123  
Transfer: ₦50,000  
The customer must not be debited twice.  

## Functional Requirements

The system must:  
Accept idempotency identifiers for appropriate financial operations.  
Associate a key with the requesting customer/client.  
Detect previously processed requests.  
Detect concurrent requests using the same key.  
Prevent a key from being reused with materially different request data.  
Define idempotency retention/expiration.  
Define behaviour for processing, successful, and failed requests.  

## Acceptance Criteria

Given a transfer successfully executes with idempotency key ABC123  
When the same request is submitted again using ABC123  
Then no additional financial movement must occur  
And the appropriate original result should be returned.  
Given ABC123 was used for ₦10,000  
When it is reused for a materially different ₦50,000 request  
Then the request must be rejected.
