# Epic 30 — Data Integrity & Data Management

## Objective

Maintain accurate, consistent, secure, and traceable financial data.  

## Requirements

The platform must consider:  
Unique identifiers.  
Referential integrity.  
Duplicate prevention.  
Required fields.  
Valid state transitions.  
Data retention.  
Archiving.  
PII handling.  
Data ownership.  
Data consistency.  
Data classification.  
Microservices must own their respective data.  
Direct cross-service database manipulation must not be used as ordinary business integration.  

## Acceptance Criteria

Duplicate unique transaction references must not result in two independent financial transactions.  
Required financial records must not be silently deleted simply because a customer closes an account.
