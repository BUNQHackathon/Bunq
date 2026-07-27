# Account Takeover and Customer Authentication Policy

**Version:** 1.3
**Owner:** Head of Customer Authentication
**Effective Date:** 1 March 2025
**Applies to:** Northwind Bank N.V. and all branches, agents, and outsourcing providers acting on its behalf

## 1. Purpose

This policy sets out the authentication and account-takeover prevention controls Northwind applies to protect customer access to digital banking channels.

## 2. Scope

This policy applies to all customer login, payment authorisation, and account maintenance activity conducted through Northwind's digital channels.

## 3. Policy Statements

13.1 The Northwind Authentication Gateway must require strong customer authentication, using at least two independent factors, for customer login and for any payment above a risk-based threshold.

13.2 Customer Onboarding must enrol every new customer in step-up authentication at the time the digital banking channel is first activated.

13.3 The Northwind Authentication Gateway must lock a customer's account after five consecutive failed login attempts and require identity re-verification before the account is unlocked.

13.4 Where a customer's registered mobile number, email address, or device is changed, the Northwind Authentication Gateway must apply a cooling-off period of 24 hours before that change can be used to authorise a payment.

13.5 The Fraud Operations Manager must review session and device fingerprinting alerts indicating a likely account takeover within one business hour of generation.

13.6 Where account takeover is confirmed, Fraud Analysts must suspend the affected account, force a password and credential reset, and notify the customer through a verified channel.

13.7 The Head of Customer Authentication must review authentication method strength and step-up thresholds at least once every twelve months.

13.8 Customer Onboarding must never accept a change of registered payee or beneficiary details communicated solely by email or unauthenticated telephone call.

13.9 The Northwind Authentication Gateway must notify a customer through an independent channel whenever a new device is registered to their account.

## 4. Roles & Responsibilities

- **Head of Customer Authentication:** owns this policy and sets authentication and step-up thresholds.
- **Fraud Analysts:** respond to confirmed account takeover events.

## 5. Related Documents

- Payment Fraud and Scam Prevention Policy
- Information Security and Access Management Policy
