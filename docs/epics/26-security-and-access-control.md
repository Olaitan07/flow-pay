# Epic 26 — Security & Access Control

## Objective

Protect customer money, data, and platform resources.  

## Requirements

The platform must address:  
Authentication.  
Authorization.  
Role-based access control.  
Resource-level authorization.  
MFA where appropriate.  
Encryption in transit.  
Encryption at rest where appropriate.  
Secret management.  
PII protection.  
Input validation.  
Session/token management.  
Sensitive-data masking.  
Secure credential storage.  
API security.  
Security auditing.  

## Acceptance Criteria

Being authenticated must not automatically allow access to another customer's resources.  
Example:  
Customer A  

GET /wallets/customer-b-wallet  
must be rejected unless Customer A is explicitly authorized.  
Passwords, tokens, PINs, OTPs, private keys, and similar secrets must not appear in ordinary logs.
