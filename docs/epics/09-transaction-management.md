# Epic 9 — Transaction Management

## Objective

Maintain the lifecycle and history of financial transactions.  

## User Story

As a customer,  
I want to view my transactions and their statuses,  
so that I know what happened to my money.  

## Transaction States

Possible states:  
INITIATED  
PENDING  
PROCESSING  
SUCCESS  
FAILED  
REVERSED  

## Functional Requirements

Every transaction should include:  
Transaction ID.  
Unique reference.  
Customer.  
Type.  
Amount.  
Currency.  
Status.  
Created time.  
Updated time where appropriate.  
State transitions must follow defined rules.  

## Acceptance Criteria

A transaction cannot silently transition between incompatible states.  
Every meaningful state change must be traceable.  
Customers must only access transactions they are authorized to view.
