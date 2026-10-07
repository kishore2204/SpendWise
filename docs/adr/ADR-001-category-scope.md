# ADR-001: Category scope and merchant-rule learning
- Status: Accepted. The default for merchant-rule learning is pending user confirmation (see "Open question").
- Date: 2026-10-07
- Council: Android Architect, Security & Data Engineer, Product/UX Architect, Devil's Advocate, Simplicity Champion, Performance Optimizer

## Context
The original spec says every account has "its own categories". Account isolation (CLAUDE.md rule #1) is about money: balances, transactions, budgets, analytics. We needed to decide whether category *definitions* are per-account, and whether learned merchant→category corrections are shared.

## Options considered
1. A global category catalogue. Budgets, analytics and rules are keyed by account.
2. Fully per-account category tables (each account gets its own copy).
3. Global defaults plus account-specific custom categories (`Category.accountId` nullable).

## Council positions
| Seat | Categories | Merchant rules | Confidence |
|---|---|---|---|
| Android Architect | 1 + per-account overlay | Per-account, then shipped defaults | Medium-high |
| Security & Data | 1, archive not delete | Per-account, then shipped defaults | High |
| Product/UX | 1 + per-account overlay | Global by default, optional per-account override | High / Medium |
| Devil's Advocate | 1 + per-account overlay | Two-level: account rule, then global rule | Medium |
| Simplicity | 1 | Global | High |
| Performance | 3 | Per-account | High |

Five of six seats chose option 1. Two seats independently flagged that option 3's nullable `accountId` is a trap: SQLite treats NULLs as distinct in UNIQUE indexes, so you get duplicate "Food" rows; every query needs `IS NULL OR =`; and a plain foreign key can't stop a transaction in account X from referencing account Y's category.

Merchant rules split 3–3. This is a preference question, not an engineering fact.

## Decision
- **One global `Category` catalogue** with stable IDs. Categories are **archived, never hard-deleted**, so history keeps its labels.
- **System categories** that can't be edited or deleted: `Transfer`, `Refund`, `Adjustment`. A transfer's two sides share the single `Transfer` category.
- **A per-account overlay:** `AccountCategory(accountId, categoryId, hidden, sortOrder)`. Each account can show, hide and order its own set. This keeps the spirit of "each account has its own categories" without copying them.
- **Two-level merchant rules:** `MerchantRule(accountId, merchantKey, categoryId)` with `UNIQUE(accountId, merchantKey)`. **`accountId = 0` is a sentinel meaning global.** We never use NULL.
  - Lookup order: the account's own rule → the global rule → the shipped default dictionary (Swiggy→Food, Uber→Transport…).
  - The correction sheet offers "This account only" or "All accounts".
- Budgets, analytics, insights and recurring items are keyed by `(accountId, categoryId)` and are never aggregated across accounts.

## Why
Categories are labels, not money. Sharing them can't move one account's figures into another's, because every financial query stays scoped to `accountId`.

Option 2 multiplies seed data, renames and backup remapping, and lets "Food" drift between accounts.

Option 3 has the integrity problems listed above.

The two-level rule table supports both learning preferences with one schema. Which one is the default is a setting, not a migration.

## Consequences / implementation rules
- Transaction → Category is a plain foreign key, with `ON DELETE RESTRICT` (categories are archived instead).
- Category pickers read the account's overlay and exclude archived categories.
- New accounts get an overlay with every non-system category visible.
- Backup and restore write a single category table. No per-account remapping.

## Open question for the user
When you correct a merchant's category, should it apply to **all accounts by default** ("teach once"), or to **this account only by default** (strict isolation)? The schema supports both. The current default is "This account only", because it matches the spirit of the spec most closely.

## Revisit if
- The user wants categories with the same name to mean different things in different accounts (e.g. a business account). In that case, adopt option 3 using the `0` sentinel, not NULL.
