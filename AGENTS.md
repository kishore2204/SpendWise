# SpendWise

Local-first personal expense tracker for one user on one Samsung phone.
This file holds the **non-negotiable rules**. Detailed knowledge lives in `docs/`; the reasons behind major decisions live in `docs/adr/`.

## Target

- Device: Samsung, **Android 16 / One UI 8.5**. Sideloaded APK only — never published to Google Play.
- The user's accounts today: **Indian Bank** and **HDFC** (+ a Cash wallet). Verified real samples: Indian Bank UPI debit, HDFC UPI debit (`docs/sms/samples/`). Other templates come from public patterns and are capped at NEEDS_REVIEW until real samples verify them.
- Stack: Kotlin, Coroutines/Flow/StateFlow, Jetpack Compose + Material 3, Room, WorkManager (deferred work only), Vico charts.
- Single Gradle `app` module, organized by package. No multi-module split in V1.
- Build JDK: Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`). The system `java` on PATH is JDK 22 — do not use it for Gradle.

## Account isolation (rule #1)

- Every financial query is scoped to a single `accountId`. Data for different accounts is never summed or mixed.
- No combined balance, income, expense, category, trend, chart, budget or insight. No "All Accounts" mode.
- The selected account is the single source of `accountId` for Home, Transactions, Insights, Budgets, Recurring and Search.
- Banks are data, never hardcoded. Accounts are identified by `(bank, accountIdentifier)`.
- A transaction from an unknown account is **never** auto-created: ask the user "Add account" or "Not mine". Remember "Not mine".
- Category *definitions* are global labels; everything with money in it is per-account. Categories are archived, never hard-deleted. Merchant rules use `accountId = 0` as the global sentinel, never NULL. See ADR-001.

## Money

- Store amounts as `Long` **paise**. Never use `Float`/`Double` for money — not in entities, math, or parsing.
- Currency is INR. Format only at the UI edge.
- Timestamps are epoch millis; bank dates are parsed as `Asia/Kolkata`.

## Transaction integrity

- Lifecycle: `PENDING → CONFIRMED` or `PENDING → IGNORED`. **Pending and ignored transactions never affect a balance, total or analytics.**
- Confirmed expense decreases the balance; confirmed income increases it.
- A transfer is one record with two linked sides (debit in the source account, credit in the destination). It is **never** income or expense.
- An ATM withdrawal into the Cash account is a transfer (bank → Cash).
- A refund links to its original transaction; analytics show the net effect.
- The sum of a split's parts equals the parent amount, exactly, in paise.
- Balances are computed, never cached, from **anchors** (ADR-004): displayed = latest anchor + Σ confirmed legs after it. A manual edit or reconcile appends an anchor; it never edits or deletes transactions. Imported history before an anchor can't change today's balance.
- An SMS-reported balance is only a suggestion after its transaction is confirmed. Reconciliation requires explicit confirmation, freshness/anchor revalidation and an append-only `AccountLedger` anchor; never auto-adjust or infer a missing transaction (ADR-012).
- Home and Insights use shared `Asia/Kolkata` calendar periods; weeks begin Monday. Every total remains scoped to one selected account (ADR-013).
- All balance/analytics queries read the `v_confirmed_txn` view — never the raw `txn` table.
- Credit cards: the user enters what they OWE; `AccountLedger.storedBalance` stores it negative (create, edit, reconcile). Never re-implement that sign rule elsewhere.
- `balance_anchor` is append-only (SQL triggers). Write anchors only through `AccountLedger`.
- Duplicates are rejected. The same transaction can arrive via SMS, notification, historical import and pull-to-refresh, and must still produce exactly one record.
- Uncertain parses (anything below high confidence) go to review. They are never auto-confirmed.

## SMS

- New messages are handled event-driven (`SMS_RECEIVED` broadcast). Never scan the inbox continuously or on a timer.
- Inbox reads happen only (a) once during the historical import the user starts, and (b) on pull-to-refresh.
- Pull-to-refresh re-reads allowlisted senders from `lastProcessedDate − 72h`; the message fingerprint makes it idempotent. Never rely on the unread flag or `_id`.
- Only allowlisted DLT senders can create transactions. Financial-looking SMS that no template matches go to "Couldn't read" — never silently dropped.
- Transfers between own accounts are suggested, never auto-paired.
- Parsing must never crash the app. A malformed or unknown SMS becomes "unparsed", not an exception.
- Parsers are modular: a generic parser plus one parser per bank under `transaction/bankparsers/<bank>/`. Add a bank with `/add-bank-parser`.

## Privacy

- Financial data stays on the device. **The manifest has no `INTERNET` permission.** No backend, no analytics SDKs, no crash reporters.
- `android:allowBackup="false"` **plus** `dataExtractionRules` excluding everything (incl. device-transfer). Backups are user-initiated files encrypted with a user passphrase — never Keystore-dependent. See ADR-002.
- The daily-use build is release (`debuggable=false`); debug uses the `.debug` applicationId and test data only.
- Never log SMS bodies, amounts or account numbers. No amounts in lock-screen notifications.
- When app-lock state is locked or unresolved, cover financial screens before drawing and remove their accessibility semantics. File pickers follow the normal lock timeout; they never create a bypass.
- Never send SMS text, amounts, merchants or account numbers to any external service, including external AI council members.
- Test fixtures use anonymized SMS (fake account digits, fake person names).

## UI

- Visual identity: an original take on Liquid Glass + Samsung One UI + premium fintech. Don't copy Apple layouts, icons or assets.
- Glass per ADR-009 + ADR-010: a cached monochrome guilloché pattern sits behind everything so glass visibly refracts. `liquidGlass(kind = Control)` for floating controls, `liquidGlass(kind = Card)` for content cards. Neutral palette: one accent (primary action + selection), income/expense hues only on money, monochrome icon badges, no colour pools, no `vibrancy()`. Never hand-roll blur; never draw the pattern per frame.
- Dark AMOLED by default, plus Light and System. The light theme is designed separately, not inverted.
- Navigation: Home · Accounts · Insights · More, plus a primary "+ Expense" action.

## How we work

- Build in phases (see `docs/development/phases.md`). Commit at the end of each phase: `feat: <phase summary>`.
- For major decisions, run `/council <decision>` and record the result as an ADR in `docs/adr/`. Don't use it for small choices.
- Before finishing any change that touches queries, repositories, ViewModels, analytics or balances, run the `account-isolation-auditor` and `transaction-integrity-auditor` agents on the diff.
- Every parser, balance and analytics calculation has unit tests. Run `./gradlew testDebugUnitTest` before committing.
- Deploy to the phone with `/deploy-to-phone`.
