# ADR-014: Learn merchant categories only from a confirmed user correction
- Status: Accepted
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Devil's Advocate, Simplicity Champion

## Context
An SMS parser may not recognize a merchant category. The user wants a category chosen during review to be suggested the next time that merchant appears. ADR-001 already sets the default scope: a category correction teaches all accounts, with account-specific rules available and taking precedence.

## Options considered
1. Learn from every parsed or pending category suggestion.
2. Learn only after the user confirms a category correction, using existing rules.
3. Add a separate learned-alias schema or ML classifier.

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | Persist normalized merchant rule on confirmed correction; prefer account override | High |
| Security & Data Engineer | Learn only after confirmation and category-kind validation; flagged global default as an isolation risk | High |
| Product / UX Architect | Future matching is a suggestion; a user can always override it | High |
| Devil's Advocate | Exact normalized keys avoid substring collisions; do not learn from ambiguous aliases | High |
| Simplicity Champion | Reuse `merchant_rule`; no table or ML needed | High |

## Decision
Keep ADR-001's user-approved global-by-default behavior and existing two-level rule table. A review category correction is saved only when the transaction is confirmed; the normalized exact merchant key becomes the future suggestion. Account-specific rules override global rules. New suggestions never silently rewrite confirmed history, and transfers, refunds, and ignored rows do not teach a merchant category.

## Why (and why not the alternatives)
The current transaction ledger and ingestion pipeline already implement this behavior and include a cross-account future-SMS test. Learning from pending rows would persist an unreviewed guess. A new schema or ML model would duplicate the deterministic rule flow.

## Consequences / implementation rules
- Keep the global default because the user explicitly selected it in ADR-001; an auditor's stricter account-only preference does not supersede that product decision.
- Rule lookup uses normalized exact `merchantKey`, with account-specific precedence.
- Category kind must match transaction kind; only successful confirmation/edit writes a rule.
- Pending rows with the same normalized merchant may receive the newly learned suggestion; confirmed rows are not rewritten.
- Add/retain regression tests for first correction, next SMS, later correction and confirmed-history preservation.

## Revisit if
The user changes the all-accounts default, or real samples show a merchant key is too ambiguous to share safely.
