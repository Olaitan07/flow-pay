# Epic 12 — Transaction Limits

## Objective

Control financial exposure and satisfy business, risk, and regulatory requirements.  

## User Story

As the financial platform,  
I want to enforce transaction limits,  
so that customers cannot exceed permitted thresholds.  
Supported Limits  
Per transaction.  
Daily.  
Weekly.  
Monthly.  
KYC-tier based.  
Transaction count.  
Velocity-based.  

## Acceptance Criteria

Given  
Daily Limit = ₦500,000  
Already Used = ₦450,000  
New Transfer = ₦100,000  
When the transfer is attempted  
Then it must be rejected  
And no financial movement should occur.  
Concurrent transactions must not bypass configured limits.
