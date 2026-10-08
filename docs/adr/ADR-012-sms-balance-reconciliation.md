# ADR-012: Reconcile a bank SMS balance with an explicit, safe adjustment
- Status: Accepted
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Devil's Advocate, Simplicity Champion

## Context
Some Indian bank transaction SMS messages include an available balance. It is a bank snapshot, and may differ from the app because of holds, pending activity, delayed messages, or transactions not yet reviewed. A pending transaction must not affect the displayed balance. All balance edits must append an account-specific anchor through `AccountLedger`.

## Options considered
1. Automatically replace the displayed balance from SMS.
2. Let the user explicitly reconcile after confirming the transaction.
3. Ignore SMS balances and require manual balance editing.

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | Compare after transaction confirmation; append a `RECONCILE` anchor; preserve later confirmed activity | High |
| Security & Data Engineer | Verify account, anchor timing and values in one Room transaction; never act on pending SMS | High |
| Product / UX Architect | Show account, timestamp, SMS amount and app estimate; offer Reconcile / Not now | High |
| Devil's Advocate | Treat as a snapshot, warn that holds/pending bank activity can explain it, reject stale snapshots | High on safeguards |
| Simplicity Champion | Reuse anchors and existing SMS fields; do not add tables or automatic adjustment | High |

## Decision
After an SMS transaction is confirmed, compare its reported balance to the app's estimated account balance at the message time. For a recent, unambiguous savings/current/other account snapshot with no later anchor, show a dismissible confirmation dialog. Reconcile appends an anchor; it does not change the transaction or earlier anchors. Confirmed account legs after the SMS are preserved by applying the snapshot difference to the current displayed amount. Credit-card available-credit values, old messages, and messages older than a later anchor are not offered for reconciliation.

## Why (and why not the alternatives)
Automatic replacement can discard known transactions and confuse available balance with settled balance. Ignoring the value misses a useful reconciliation opportunity. The explicit action preserves ledger history and lets the user reject differences caused by holds or other bank activity.

## Consequences / implementation rules
- The transaction is confirmed before the dialog appears; pending and ignored rows never prompt or change balances.
- The dialog identifies the account and message time, distinguishes the SMS snapshot from the app estimate, and says later confirmed transactions are preserved.
- Applying revalidates freshness, account, and anchor timing inside a Room transaction, then writes through `AccountLedger` with source `RECONCILE`.
- The operation is account-scoped and uses `Long` paise. It never infers a missing transaction from the difference.

## Revisit if
Verified bank formats establish different balance semantics, or the parser can reliably distinguish available credit, holds, and settled balance.
