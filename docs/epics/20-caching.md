# Epic 20 — Caching

## Objective

Improve performance without compromising financial correctness.  
Suitable Candidates  
Examples include:  
Supported currencies.  
Bank directories.  
Country configuration.  
Product configuration.  
Feature configuration.  
Appropriate reference data.  
Exchange-rate snapshots with explicit validity.  

## Financial Rule

A stale cache must not independently authorize a financial operation when current authoritative state is required.  
Example:  
Cached balance = ₦100,000  
Authoritative available balance = ₦20,000  
A ₦50,000 transfer must not succeed merely because the cached value says ₦100,000.  

## Functional Requirements

Every cached domain must define:  
TTL.  
Invalidation rules.  
Source of truth.  
Behaviour when cache is unavailable.  
Acceptable staleness.
