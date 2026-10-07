---
name: add-bank-parser
description: Add or extend SMS parsing for a bank (e.g. Axis, Kotak, Canara) through a repeatable, test-first workflow, without modifying the core SMS engine. Use when the user shares new bank SMS samples or a bank's messages aren't being parsed.
argument-hint: <bank name>
---

# /add-bank-parser <bank>

The core pipeline (detection → bank detection → parsing → dedup → pending) stays unchanged. A bank is added only by adding a parser module, fixtures and a registry entry.

## 1. Collect samples

- Get real SMS from the user: screenshots or pasted text. Aim for at least one of each: UPI debit, UPI credit, card/POS, ATM, NEFT/IMPS in and out, refund/reversal, and, if relevant, credit-card spend, credit-card bill payment and balance/OTP noise (non-transactions).
- Record the sender IDs (e.g. `VM-INDBNK-S`). DLT headers look like `XX-BANKID-S`. Match on the `BANKID` part, because the 2-letter prefix varies by operator and circle.
- **Anonymize before saving:** replace account digits, personal names, reference numbers and balances with fake but format-identical values. Keep the bank's own helpline numbers and the exact punctuation, spacing and casing.

## 2. Document the formats

Add or update `docs/sms/samples/<bank>.md`: each template, the field each part maps to, the date format, amount format (commas? `Rs.`/`INR`/`₹`?), and quirks (e.g. the merchant directly followed by `.RRN` with no space).

## 3. Write fixtures and tests first

- Put fixtures in `app/src/test/resources/sms/<bank>/<case>.txt` (first line `sender: <id>`, the rest is the body), with expected results in the test.
- Write the parser tests before the parser. Cover: every field (amount in paise, direction, type, account last-4, merchant, date, reference, balance, payment method), the confidence level, and that non-transaction messages (OTP, promo, balance enquiry) return "not a transaction".
- Add a duplicate test: parsing the same message twice, or the same reference arriving via notification, yields one transaction.
- Add an account-detection test: an unknown last-4 produces a "new account detected" result, not a created account.

## 4. Implement

- Create `transaction/bankparsers/<bank>/` with a parser implementing the bank-parser interface. Reuse the shared extractors in `transaction/parsing/` (amount, date, merchant, etc.), and only override what the bank needs.
- Register the bank's sender patterns in the bank registry. The parser must return a result type, never throw. Unknown templates fall through to the generic parser with lower confidence.
- No `Double` for amounts. Parse straight to paise.

## 5. Verify

- Run `./gradlew testDebugUnitTest --tests "*<Bank>*"`, then the full unit test suite to check for regressions in other banks.
- Run the `transaction-integrity-auditor` agent on the diff.
- Report to the user which templates are supported, their confidence levels, and which sample types are still missing.
