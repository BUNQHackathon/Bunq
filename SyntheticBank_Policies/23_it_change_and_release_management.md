# IT Change and Release Management Policy

**Version:** 1.1
**Owner:** Head of IT Change Management
**Effective Date:** 1 March 2025
**Applies to:** Northwind Bank N.V. and all branches, agents, and outsourcing providers acting on its behalf

## 1. Purpose

This policy sets out the controls Northwind operates to plan, test, approve, and deploy changes to its production systems.

## 2. Scope

This policy applies to all changes to production systems, including systems supporting customer due diligence, transaction monitoring, sanctions screening, and payment processing.

## 3. Policy Statements

23.1 Every change to a production system must be recorded in the Northwind Change Management System and approved by the Change Advisory Board before deployment.

23.2 A change classified as high risk must be tested in a non-production environment and must include a documented rollback plan before it is approved for deployment.

23.3 Emergency changes deployed outside the standard change window must be approved retrospectively by the Change Advisory Board within one business day.

23.4 The Head of IT Change Management must ensure that changes affecting systems used for customer due diligence, transaction monitoring, or sanctions screening are reviewed by Compliance before deployment.

23.5 Access to deploy code into the production environment must be restricted to authorised release engineers and must be separate from the access rights of developers who wrote the code.

23.6 The Head of IT Change Management must maintain a record of all deployed changes, including the associated approval, for at least three years.

23.7 The Head of IT Change Management must ensure that a post-implementation review is performed for every high-risk change within ten business days of deployment.

## 4. Roles & Responsibilities

- **Head of IT Change Management:** owns this policy and chairs the Change Advisory Board.
- **Release engineers:** deploy approved changes into the production environment.

## 5. Related Documents

- Information Security and Access Management Policy
- Business Continuity and Operational Resilience Policy
