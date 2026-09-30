# Epic 10 — Ledger & Double-Entry Accounting

## Objective

Maintain an immutable accounting record of financial movements.  

## System Requirement

Every posted financial movement must be represented by balanced accounting entries.  
Example:  
Customer A → Customer B = ₦10,000  

Account               Debit       Credit  

Sender Wallet         ₦10,000  
Receiver Wallet                    ₦10,000  
------------------------------------------  
TOTAL                  ₦10,000     ₦10,000  

## Functional Requirements

Every journal transaction must balance.  
Ledger entries must reference the originating financial transaction.  
Posted entries must be immutable.  
Corrections must use reversals or compensating entries.  
Duplicate events must not create duplicate ledger postings.  
Ledger history must remain auditable.  

## Acceptance Criteria

Given ₦10,000 successfully moves from A to B  
Then corresponding debit and credit entries must exist  
And:  
Total Debit = Total Credit  
Given a ledger entry has been posted  
When a correction is necessary  
Then the original entry must remain intact  
And the correction must use an approved compensating mechanism.
