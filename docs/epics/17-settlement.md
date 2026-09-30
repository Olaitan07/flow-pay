# Epic 17 — Settlement

## Objective

Track the movement and settlement of funds between financial parties.  

## Product Requirement

Transaction success and settlement status may be separate concepts.  
Possible settlement states:  
NOT_READY  
PENDING  
PROCESSING  
SETTLED  
FAILED  

## Functional Requirements

The platform must be able to associate eligible transactions with settlement records where the business model requires settlement.  
Settlement failures must not silently change historical transaction records.  

## Acceptance Criteria

A payment marked successful but awaiting institutional settlement must remain distinguishable from one that has completed settlement.
