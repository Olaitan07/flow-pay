# Epic 6 — P2P Transfers

## Objective

Allow FlowPay customers to transfer money to each other.  

## User Story

As a customer,  
I want to send money to another FlowPay customer,  
so that the recipient receives funds.  
Transfer Information  
Sender  
Recipient  
Amount  
Currency  
Narration  
Reference  
Idempotency Identifier  

## Functional Requirements

Before executing a transfer, validate:  
Sender.  
Recipient.  
Wallet status.  
Currency.  
Amount.  
Available balance.  
KYC restrictions.  
Transaction limits.  
Risk restrictions.  

## Acceptance Criteria

Given  
Available Balance = ₦50,000  
Transfer Amount   = ₦10,000  
Fee               = ₦100  
When the transfer succeeds  
Then  
Sender total debit = ₦10,100  
Recipient credit   = ₦10,000  
Fee                 = ₦100  
And the transaction must be recorded  
And the ledger must balance.  
Transfers containing zero or negative amounts must be rejected.
