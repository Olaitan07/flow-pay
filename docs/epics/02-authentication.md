# Epic 2 — Authentication

## Objective

Protect customer accounts from unauthorized access.  

## User Story

As a registered customer,  
I want to authenticate securely,  
so that only authorized users can access my account.  

## Functional Requirements

The system should support:  
Login.  
Logout.  
Password reset.  
Session/token renewal.  
MFA/OTP where required.  
Failed-login protection.  
Account lock or challenge policies.  
Session revocation.  
Passwords must never be stored in plain text.  

## Acceptance Criteria

Given valid credentials  
When the customer authenticates  
Then access should be granted.  
Given incorrect credentials  
When authentication is attempted  
Then access must be rejected.  
Repeated failed authentication attempts must trigger configured security controls.  
Authentication errors must not unnecessarily expose whether sensitive account information exists.
