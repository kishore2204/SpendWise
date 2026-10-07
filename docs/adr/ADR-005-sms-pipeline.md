# ADR-005: SMS ingestion pipeline
- Status: Accepted
- Date: 2026-10-07
- Council (SMS): Android SMS Platform Engineer, Parser Architect, Privacy Officer + Devil's Advocate, Simplicity Champion

## Decision

**1. Receiving.**
- A manifest `SMS_RECEIVED` receiver, `exported=true`, protected by `android.permission.BROADCAST_SMS`.
- Inside `goAsync()` it does the following, all in under a second:
  1. Join the multipart message from `getMessagesFromIntent()`.
  2. **Sender gate:** drop the message unless the sender's DLT token is on the bank allowlist.
  3. `INSERT OR IGNORE` a `raw_message` row with status RECEIVED.
  4. Enqueue expedited unique work `"ingest"` (APPEND_OR_REPLACE).
- WorkManager is never used to *receive*. It only runs the processor, so a parser crash can't lose a message.
- **One processor.** It drains every RECEIVED row, serialized by unique work plus a `Mutex`, and calls `ProcessIncoming` (ADR-003). The receiver, pull-to-refresh and historical import all feed this same path.

**2. Fingerprint** (exactly-once at the message level).
- `sha256(dltToken | sentAtSeconds | normalizedBody)`, where `normalizedBody` = whitespace collapsed (HDFC bodies contain newlines) and trimmed.
- `sentAt` is `SmsMessage.timestampMillis` from the broadcast, which equals `date_sent` in the inbox provider. So the same SMS has the same key on every path, and two genuinely identical payment texts still differ.
- If `date_sent = 0`, use `receivedAt` floored to the minute instead. Any duplicate that slips through is caught by the bankRef unique index (ADR-004).

**3. Pull-to-refresh** (the user pulls down).
- Query `Telephony.Sms.Inbox` with projection `_id, address, body, date, date_sent`.
- Filter by the allowlisted senders at query level, and only fetch `date ≥ lastProcessedDate − 72h`.
- Stage the results and drain them. The 72h overlap catches anything the receiver missed (after a reboot, or while the app was deep-sleeping), and the fingerprint stops repeats.
- **The unread flag and `_id` are not used.** Samsung Messages marks a message read when it's opened, and `_id` order breaks after a Messages restore.
- The result is shown as "3 new transactions found".

**4. Historical import** (once, started by the user during onboarding or from Settings).
- The user picks a range: 30, 90 (default), 180 or 365 days.
- It runs in a long-running worker (`setForeground`, type `dataSync`) and pages by 7-day date windows. It checkpoints after each window, can be resumed or cancelled, and shows progress.
- Results land in a **Review imported** screen. Items are grouped by merchant/template, with a "Confirm all high-confidence (N)" button. Nothing is confirmed automatically.
- Imported history can't change today's balance, because balances are anchor-based (ADR-004).

**5. The financial gate runs before the parsers** (Devil's Advocate).
- Allowlisted sender + an amount + a money verb or `A/c` → the message is treated as financial.
- If it's financial but no template matches, its status is **FINANCIAL_UNPARSED** and it goes to a "Couldn't read" list. It is never dropped silently.
- If the unparsed count for one sender rises, the app warns: "HDFC format may have changed".
- OTP, promo, failed/declined and "will be debited" mandate reminders are classified NOT_FINANCIAL.

**6. Transfer pairing.**
- After each commit, look for an opposite-sign transaction in another owned account, not already paired.
  - A shared RRN → suggest "Transfer Indian Bank → HDFC?" with high confidence.
  - Same amount and |Δt| ≤ 10 min → a softer suggestion.
  - Several candidates → review.
- **A transfer is never paired automatically.** A ₹500 payment and an unrelated ₹500 receipt are not a transfer.
- An optional "Your name" setting: a P2P payee that matches it suggests "Transfer to your own account?".
- An ATM withdrawal is proposed as a bank → Cash transfer.

**7. Unknown accounts.**
- The bank is recognised but its last4 isn't an account in the app, and it isn't in `not_mine`. The app shows "New account detected · HDFC ••••1234 · Not mine / Add account".
- **Not mine** is remembered in `not_mine`, and those messages' bodies are deleted (only the fingerprint is kept).

**8. Notifications.**
- Channel "New transactions", default importance, grouped.
- Live SMS → "₹450 · SWIGGY · HDFC ••4521" with actions **Confirm** (`setAuthenticationRequired(true)`), **Edit** and **Ignore**. Low confidence gets a single **Review** action.
- `VISIBILITY_PRIVATE`, with the public version "New transaction · unlock to view".
- After a reboot, until the next refresh: a banner saying "Pull to refresh to catch up". It never triggers a scan.

**9. Permissions and onboarding.**
- Show a rationale, then request `RECEIVE_SMS` + `READ_SMS`, then `POST_NOTIFICATIONS`.
- Detect the instant denial caused by **restricted settings** on Android 16 sideloaded apps, and show a step-by-step guide: App info → ⋮ → Allow restricted settings.
- Battery guidance: Unrestricted, plus *Never sleeping apps*.
- A **health card** shows "Last SMS detected: 2h ago" and warns when the receiver seems asleep.
- If permission is denied, manual entry, accounts and analytics all still work. SMS features show "Off · Set up".

**10. Privacy** (Privacy Officer).
- **The manifest has no `INTERNET` permission.** The app physically can't send data anywhere.
- Raw bodies are stored only for allowlisted, financial messages. Before storage, OTP/CVV/PIN digits and URLs are redacted.
- NOT_FINANCIAL, IGNORED and NOT_MINE rows keep the fingerprint only, never the body.
- Settings has a "Purge raw SMS text" action, which keeps the parsed fields.
- Logs contain only IDs, template IDs and result codes. Number parsing is wrapped, because `NumberFormatException` messages echo their input.

## Deferred to V2
- **Notification listener.** UPI app notifications lack last4, so they could only be fuzzy-deduped. It also needs a second restricted-settings grant. If it's added later, it's corroboration-only.
- Auto-confirming high-confidence items (a setting).

## Rejected
- **Parsing inside the receiver:** time limit, and a crash loses the SMS.
- **Foreground service:** not needed for receiving SMS.
- **`ContentObserver` on the inbox:** it amounts to continuous scanning.
- **A scan on every boot:** violates the no-scan rule.
- **Watermarks based on `_id` or the read flag:** unreliable on Samsung.

## Revisit if
- One UI deep sleep still drops broadcasts even after the battery guidance.
- `date_sent` turns out to differ between the broadcast and the provider on this device. Verify this on the phone in Phase 5.
