# ADR-004: Transaction model, schema and dedup
- Status: Accepted
- Date: 2026-10-07
- Council (Core): Android Architect, Security & Data, Devil's Advocate, Simplicity Champion, Performance Optimizer

## Transaction model

**Signed `amountPaise` per account leg** (negative = money leaves this account), plus a `kind`:
- Kinds: EXPENSE, INCOME, TRANSFER_OUT, TRANSFER_IN, REFUND, ATM_WITHDRAWAL, CASH_DEPOSIT, FEE, INTEREST, REVERSAL, UNKNOWN.
- A trigger enforces sign ⇔ kind:
  - must be negative: EXPENSE, TRANSFER_OUT, FEE, ATM_WITHDRAWAL
  - must be positive: INCOME, TRANSFER_IN, REFUND, INTEREST, CASH_DEPOSIT
  - REVERSAL and UNKNOWN: either sign, but never 0
- The council voted 3–2 for signed amounts. The balance becomes a plain `SUM`, and a reversal is simply a row with the opposite sign. The trigger answers the "silent sign bug" objection.

**Status.** Pending rows live in the same `txn` table, with `status` ∈ PENDING / CONFIRMED / IGNORED.
- **Every balance and analytics DAO reads only the view `v_confirmed_txn`.** Forgetting the status filter is impossible by construction. This answers the Devil's request for a staging table.
- IGNORED rows are kept, without the raw body, so their dedup keys keep blocking re-imports.

**Transfers.** Each side is an independent leg (TRANSFER_OUT / TRANSFER_IN). Legs are linked by `transfer(id, fromTxnId UNIQUE, toTxnId UNIQUE)` once both sides are known.
- Sides can arrive hours apart, or only one may ever arrive.
- A trigger requires the two legs to be in different accounts.
- Transfer kinds are excluded from income and expense everywhere.
- An ATM withdrawal creates the bank leg plus a Cash leg in a single `CreateTransfer`.

**Refunds.** `refundOfId` links a refund to the original.
- A trigger requires the same account and the opposite sign. Kotlin checks that Σ refunds ≤ |original|.
- Analytics nets the refund against the original's category, in the month the refund lands.
- A refund that arrives in a different account stays an unlinked REFUND in that account. Linking across accounts would break isolation.

**Splits.** Stored in `txn_split(txnId, categoryId, amountPaise)`.
- A split parent has `categoryId = NULL`.
- `SetSplits` replaces the whole set and asserts Σ = parent before commit.
- Changing the parent's amount clears its splits and asks the user to re-split.
- Category analytics use `COALESCE(split, parent)` through a view.
- A refund on a split parent defaults to the category of the largest split.

**Balance: computed, never cached, with anchors** (Devil's Advocate; the most important change from the original spec).
- Table: `balance_anchor(accountId, effectiveAt, balancePaise, source ∈ OPENING/MANUAL/RECONCILE, note)`, append-only.
- **Displayed balance = latest anchor + Σ confirmed legs with occurredAt > anchor.effectiveAt.**
- **Why anchors, not delta adjustments:** suppose you create HDFC today at ₹28,450 and then import 90 days of history. With deltas, each imported transaction would shift today's balance, so money would be counted twice. With an anchor, history before it can't change today's balance.
- The spec's formula still holds for display: *Calculated from transactions* (Σ all confirmed) **+ Adjustment** (derived = anchor − calculated as of the anchor time) **= Displayed*.
- Editing the balance adds an anchor and never touches transactions. The list of anchors is the adjustment history.
- The opening balance is the first anchor.

**SMS "Avl Bal".** Stored as `reportedBalancePaise`.
- A mismatch with the computed balance at that time *suggests* "Reconcile to ₹X", which adds a RECONCILE anchor. It's never applied automatically.
- A broken balance chain (previous − amount ≠ next) flags a possible missed SMS. This only works for Indian Bank; HDFC UPI messages carry no balance.

## Schema (V1)

| Table | Key columns / constraints |
|---|---|
| `account` | `bankCode` (INDBNK, HDFCBK, CASH, …), `last4`, `nickname`, `type`, `archivedAt`, `createdAt`. UNIQUE(bankCode, last4) |
| `not_mine` | `bankCode`, `last4` (digits only). UNIQUE |
| `raw_message` | `source`, `sender`, `body?`, `sentAt`, `receivedAt`, `fingerprint` UNIQUE, `parseStatus` (RECEIVED / PARSED / NOT_FINANCIAL / FINANCIAL_UNPARSED / NOT_MINE / ERROR), `parserVersion`, `bankHint` |
| `txn` | `accountId` FK RESTRICT, `amountPaise`, `kind`, `status`, `confidence`, `occurredAt`, `localEpochDay` (IST), `timeSource` (BODY/RECEIVED/MANUAL), `categoryId?`, `merchantRaw?`, `merchantKey?`, `counterpartyKind`, `paymentMethod`, `bankRef?`, `reportedBalancePaise?`, `transferId?`, `refundOfId?`, `possibleDupOfId?`, `note?`, timestamps |
| `txn_source` | `txnId`, `rawMessageId` UNIQUE. Extra evidence for the same transaction attaches here. |
| `transfer`, `txn_split`, `balance_anchor` | as above |
| `category`, `account_category`, `merchant_rule` | per ADR-001 |
| `budget` | `accountId` FK CASCADE, `categoryId`, `period`, `limitPaise`. UNIQUE(accountId, categoryId, period) |
| `recurring_rule` | `accountId` FK CASCADE, `merchantKey`, `amountPaise`, `cadence`, `nextDueAt`, `kind` |

The merchant is stored as a normalized `merchantKey` string. A separate merchant table is deferred.

**Indexes:**
- Covering index `txn(accountId, status, localEpochDay, kind, amountPaise, categoryId, merchantKey)`, used for totals, trends, categories and merchants.
- List index `txn(accountId, occurredAt DESC, id DESC)`.
- **Partial UNIQUE `txn(accountId, bankRef, isCredit)` WHERE bankRef IS NOT NULL.** This still allows the same RRN on both legs of a self-transfer (they're in different accounts) and on a reversal (opposite direction).
- Plus indexes on the FK and link columns.
- Room can't declare partial indexes or triggers. They're created in a `RoomDatabase.Callback` and re-asserted in every migration.

**Lists and search:**
- Paging 3 with page size 50 and stable keys.
- Search: `LIKE` on merchantKey and note, scoped by `accountId`, with a 250 ms debounce. Switch to FTS only if a scan takes more than 30 ms.
- Aggregates are computed live; there are no summary tables.

## Dedup
1. **Message:** `raw_message.fingerprint` is UNIQUE (construction in ADR-005).
2. **Transaction:** the partial UNIQUE bankRef index. The database enforces it even if the app has a bug.
3. **Fuzzy** (applies when one side has no bankRef: same account, same amount and sign, |Δt| ≤ 15 min):
   - If the only candidate is a single **unlinked manual entry**, attach the SMS as evidence, fill in the missing ref and balance, and show "Matched to your manual entry · Undo".
   - Otherwise, insert as PENDING with `possibleDupOfId` set.
   - **Never auto-merge two detected transactions.** Two real ₹20 teas are two transactions.

Keys are deterministic. They never use the SMS provider's `_id` or autoincrement IDs.

## Enforcement and safety
- **SQL:** FKs, UNIQUE indexes, NOT NULL, triggers.
- **Kotlin ledger commands:** split sums, refund totals, transfer pairing.
- **Migrations:** `exportSchema = true`, MigrationTestHelper tests, never use destructive fallback, and take an automatic backup before migrating.
- **Restore:** decrypt to a temp file, run `integrity_check`, `foreign_key_check` and an invariant check, and swap the file in only if all pass.

## Revisit if
- A bank reuses a ref in the same direction on the same account.
- The balance SUM takes more than 16 ms on the device at 50k rows. If so, add monthly snapshots.
