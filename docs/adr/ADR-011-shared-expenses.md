# ADR-011: Shared expenses — "I paid for a friend, they pay me back"
- Status: Accepted (one presentation choice awaits the user — see "Open question")
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Android SMS Platform Engineer + Devil's Advocate, Simplicity Champion

## Context
The user often pays a whole bill for themself and a friend (₹30: ₹15 theirs, ₹15 the friend's). The friend repays later — usually by UPI (an incoming credit SMS, sometimes into a different account than the one that paid), sometimes in cash, sometimes as one lump for several bills, sometimes partly, sometimes never. Today the ₹30 counts as spending and the ₹15 as income: both numbers are wrong.

## Options considered
- **A.** Split the bill with a system category "To get back"; categorise the repayment "Repaid"; outstanding = Σ owed − Σ repaid.
- **B.** Receivables: `friend`, a share of a bill owed by a friend, settlements linking repayment credits to shares, a repayment kind that is never income.
- **C.** Record the repayment as a REFUND of the bill.
- **D.** Full Splitwise-style ledger (groups, both directions, simplify debts).

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | B-lite: share + settlement tables, `REPAYMENT` kind, spend view | Medium-high |
| Security & Data Engineer | B-lite with SQL caps; reject A and C | High (on rejecting A/C) |
| Product / UX Architect | B, learned payer aliases, "forgive", tag shares from review/detail | Medium-high |
| Android SMS Platform Engineer | B lean; suggestions computed like transfer pairing | Medium-high |
| Devil's Advocate | B-lite; A stores a debt as a label and four existing rules corrupt it | Medium-high |
| Simplicity Champion | A+: split part "Lent" + person table | High on scope |

## Decision
**B-lite.**
- `friend(id, name)` and `friend_alias(normalized, friendId)` — payer names/VPAs learned when the user marks a repayment. An alias only ranks a suggestion; it never classifies anything on its own.
- `shared_share(id, txnId, friendId, amountPaise, forgivenAt?)` — a part of a **confirmed EXPENSE** owed by a friend.
- `settlement(id, creditTxnId, shareId, amountPaise)` — money a friend paid back, applied to their shares (many-to-many: lump sums and partial payments).
- New kind **`REPAYMENT`** (money in, positive). Never income, never spending, never splittable; system category "Repayment".
- **Own spending** = EXPENSE + FEE − REFUND − shares. A share stops being excluded only by **forgiving** it: a forgiven share's unpaid part becomes the user's spending again (in the bill's month). Settlement never changes Spent.

| | Paying account (HDFC) | Receiving account (Indian Bank) |
|---|---|---|
| Balance | −₹30 (real money) | +₹15 (real money) |
| Spent | ₹15 from the moment the share is added, before and after repayment | — |
| "Lent" line | ₹15 | — |
| Income | — | ₹0 |
| "Paid back" line | — | ₹15 |

## Why (and why not the alternatives)
- **A** keeps a debt as a split label: editing the bill's amount silently clears splits (the debt disappears), the largest-split refund rule can hand a refund the "owed" category, merchant learning could learn it, and every income/spending query must remember a category exclusion. It also can't record which bills a lump sum paid.
- **C** is blocked by the refund trigger (same account only) and can't say who owes what.
- **D** is a different product for one person.
- B keeps the debt in its own rows with SQL caps, the repayment is excluded from income **by kind** (nothing to forget), and the cross-account link is a row — exactly like transfers — so no account figure ever includes another account's money.

## Consequences / implementation rules
- SQL triggers: a share's bill must be a confirmed EXPENSE; Σ shares + Σ refunds ≤ |bill|; a settlement's credit must be a confirmed REPAYMENT; Σ settlements ≤ share and ≤ credit.
- Ledger commands: `shareWithFriend`, `removeShare` (only while nothing is settled), `forgiveShare`, `markAsRepayment(credit, friend)` (allocates oldest open shares first, must cover the whole credit — an overpayment stays income), `undoRepayment`, `recordCashRepayment(account, friend, amount)`.
- A bill with shares can't be deleted or edited below Σ shares + refunds; refunds are capped by the same sum.
- Suggestions only, never automatic: a credit gets "Paid back by Ravi?" when its payer matches a learned alias, or its amount equals one open share or a friend's oldest open shares. A transfer suggestion (shared RRN) ranks above it. Such credits are never HIGH confidence, so "Confirm all" and the notification Confirm can't book them as income.
- Every Phase 6 analytics query takes own spending from the same rule (`LedgerDao` period summary and later category/trend queries subtract shares through `v_share_excluded`).
- Shares reduce the bill's own category (its largest split part when split).
- **Account isolation:** outstanding is shown **per friend, per paying account** ("Ravi · ₹15 from HDFC · ₹40 from Indian"). No total across accounts — see the open question.
- DB version 1 → 2 by Room AutoMigration (new tables + view only); the kind is stored by name, so no column changes; the sign trigger picks REPAYMENT up on the next open.

## Audit outcomes (2026-10-08)
- No figure across accounts is ever shown: "more than Ravi owes" instead of a total; the cash-repayment form lists what's owed per account; amount suggestions match within one paying account's bills. The oldest-first allocation of a repayment may still span accounts (a link row, like a transfer).
- Refunds are capped by refunds + friends' shares in the ledger AND in SQL (`trg_txn_check_*`).
- A credit from a person is never HIGH confidence, and one-tap confirm (notification, confirm-all) re-checks "looks like a repayment", so a friend's money is never booked as income without the user looking.
- "Not a repayment" sends an SMS credit back to review (PENDING); a hand-recorded repayment is deleted instead. It is refused once a bill it paid was forgiven (otherwise one account's action would rewrite another account's spending).
- Known limit: a bank REVERSAL of a shared bill isn't linked to it; the user removes or forgives the share by hand.

## Deferred
"A friend paid for me" (the reverse direction), groups, reminders, choosing exactly which bills a repayment covers (V1: oldest first), overpayment allocation.

## Open question (user preference)
Should the Friends screen also show one **total per friend across accounts** ("Ravi owes you ₹55")? It is a debt owed by a person, not money in an account, but it is a cross-account sum, which rule #1 forbids today. Default: per-account lines only, until the user decides.

## Revisit if
- Repayments are often split across bills in a way "oldest first" gets wrong → add manual allocation.
- Forgiving a share must not change a past month (budgets in Phase 6) → book forgiven amounts in the forgive month.
