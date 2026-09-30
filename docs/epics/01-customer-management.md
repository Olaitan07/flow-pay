# Epic 1 — Customer Management

## Objective

Allow customers to create and manage FlowPay accounts.  

## User Story 1.1 — Customer Registration

As a new customer,  
I want to register for FlowPay,  
so that I can use the platform's financial services.  

## Functional Requirements

The customer should provide:  
First name.  
Last name.  
Email.  
Phone number.  
Password.  
Country.  
The system must:  
Validate required information.  
Prevent duplicate accounts based on configured unique identifiers.  
Generate a unique customer ID.  
Record account creation time.  
Assign an initial account status.  
Possible statuses include:  
PENDING_VERIFICATION  
ACTIVE  
SUSPENDED  
BLOCKED  
CLOSED  

## Acceptance Criteria

Given valid registration information  
When the customer registers  
Then a customer account must be created  
And a unique customer ID must be generated  
And the appropriate initial status must be assigned.  
Given an account already exists using a unique email  
When registration is attempted using the same email  
Then another account must not be created.  

## User Story 1.2 — Customer Profile

As a customer,  
I want to view and maintain permitted profile information,  
so that my account information remains accurate.  
Sensitive or verified identity attributes must not be freely changed without appropriate verification.
