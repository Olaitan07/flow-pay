# Epic 23 — Rate Limiting

## Objective

Protect the platform from abuse and excessive traffic.  
Applicable Operations  
Examples:  
Login.  
OTP request.  
Password reset.  
Transfer initiation.  
Beneficiary creation.  
Account lookup.  
Transaction lookup.  

## Functional Requirements

Rate limits may differ by:  
Endpoint.  
Customer.  
Device.  
Client/application.  
Risk level.  
Rate limiting must not be confused with financial transaction limits.  

## Acceptance Criteria

Given a client exceeds an applicable request threshold  
When another request is submitted within the restricted period  
Then the system should reject or throttle it according to policy.
