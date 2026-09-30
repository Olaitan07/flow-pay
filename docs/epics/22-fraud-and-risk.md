# Epic 22 — Fraud & Risk

## Objective

Detect and respond to suspicious financial activity.  
Example Risk Signals  
Unusually high-value transaction.  
Rapid transaction velocity.  
Multiple failed authentication attempts.  
New device plus high-value transaction.  
Rapid beneficiary creation.  
Unusual destination.  
Abnormal transaction pattern.  
Possible Decisions  
ALLOW  
CHALLENGE  
REVIEW  
BLOCK  

## Acceptance Criteria

Given a transaction triggers a configured blocking risk rule  
When risk evaluation completes  
Then the financial operation must not proceed  
And the risk decision must be traceable.  
Risk rules must distinguish between business validation and fraud/risk decisions.
