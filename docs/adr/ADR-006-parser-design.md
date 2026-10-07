# ADR-006: SMS parser design
- Status: Accepted
- Date: 2026-10-07
- Council (SMS): Parser Architect, SMS Platform Engineer, Privacy + Devil's Advocate, Simplicity Champion

## Decision: hybrid templates, with small shared helpers

```kotlin
sealed interface ParseResult {
  data class Parsed(val txn: ParsedTxn, val confidence: Confidence, val reasons: Set<Reason>,
                    val parserId: String, val templateId: String?) : ParseResult
  data class NotTransaction(val kind: RejectKind) : ParseResult   // OTP, PROMO, FAILED, MANDATE_REMINDER, BALANCE_ENQUIRY
  data class Unrecognized(val bankHint: BankId?, val cause: Cause) : ParseResult
}
interface BankParser {
  val bankId: BankId; val senderTokens: Set<String>          // e.g. INDBNK, HDFCBK
  val rejects: List<Regex>; val templates: List<Template>
}
data class Template(val id: String, val regex: Regex, val verified: Boolean,
                    val map: (MatchResult, RawSms) -> ParsedTxn)
```
- All `ParsedTxn` fields are nullable, except the original SMS and its source. Amounts are `Long` paise, parsed from the string (commas stripped, at most 2 decimals) and **never via `Double`**.
- **Shared helpers** in `transaction/parsing/` are small pure functions: `AmountExtractor`, `DateExtractor` (each template names its own format), `AccountExtractor` (`*1234`, `XX1234`, `ending 1234` → `1234`), `MerchantExtractor` (normalization), `TransactionTypeDetector`. This is the Simplicity view: no extractor class hierarchy.
- **Per-bank packages** `bankparsers/<bank>/` hold sender tokens, reject patterns and **anchored templates with named groups**. Each template fixes the direction and the date format. Banks register through Hilt `@IntoSet`, so adding a bank changes no engine code.
- **Generic fallback** (`bankparsers/generic/`) is keyword + amount based. It's capped at NEEDS_REVIEW, or LOW when direction is unclear.
- **The pipeline never throws.** It runs: input cap (1,000 chars) → global rejects → routing → bank rejects → templates → generic. The whole thing is wrapped in `runCatching`, and any failure becomes `Unrecognized(cause = PARSER_ERROR)`.

## Routing
- The sender is normalized by stripping `^[A-Z]{2}-` and `-[SPTG]$` (Indian Bank sends `-S`, HDFC sends `-T`). The remaining token is looked up in the registry.
- Non-DLT senders (10-digit numbers, unknown tokens) never create transactions (ADR-005 sender gate). Phishing texts can't create fake pending items.

## Confidence: a rule table, with no numeric weights
| Level | Rule |
|---|---|
| **HIGH** | All of: trusted DLT sender, a **verified** template, amount, direction, last4, date, and no demotion reason |
| **NEEDS_REVIEW** | Any of: unverified template, generic parser, merchant missing, type UNKNOWN, body date more than 3 days from receipt, balance chain broken, possible duplicate, transfer suggestion |
| **LOW** | Amount or direction missing. Stored, but can't be confirmed without the user editing it. |

- **The balance chain** (Indian Bank only): when it holds, the message gains the `CHAIN_OK` reason. It can lift a message to HIGH *only* if "merchant missing" was the sole demotion. It never lifts past an unverified template or the generic parser.
- **Date without time:** the time comes from `sentAt`, with `timeSource = RECEIVED`. Dates are stored as an IST local day, never as UTC midnight.

## Templates in V1
| Bank | Template | Verified | Source |
|---|---|---|---|
| Indian Bank | UPI debit `Sent Rs.X from A/c *NNNN on dd-MM-yy to P.RRN N.Avl Bal Rs.Y` | ✅ | `docs/sms/samples/indian-bank.md` |
| HDFC | UPI debit, multi-line `Sent Rs.X / From HDFC Bank A/C *NNNN / To P / On dd/MM/yy / Ref N` | ✅ | `docs/sms/samples/hdfc.md` |
| HDFC | UPI credit, card spend, ATM, NEFT/IMPS credit | ❌ | Public patterns; capped at NEEDS_REVIEW until real samples arrive |
| Indian Bank | Credit, ATM, NEFT/IMPS | ❌ | Same |

## Merchant normalization
- `merchantKey` is built like this: uppercase; strip honorifics (MR, MRS, MS, SHRI, SMT, DR); strip gateway prefixes (`RAZORPAY*`, `PAYU*`); strip **full and truncated** corporate suffixes (`PRIVATE LIMITED`, `PRIVATE L`, `PVT LTD`, `PVT LT`, …); strip punctuation; collapse spaces.
- If a VPA is present, it becomes the key (lowercased).
- `counterpartyKind` is PERSON (honorific, phone-number VPA, or a short name without business words), MERCHANT or UNKNOWN. Person payments default to "needs category".

## The "Couldn't read" list
- These are `raw_message` rows with status FINANCIAL_UNPARSED, and they keep the body.
- Actions: **Add manually**, **Ignore**, **Export as fixture** (redacted JSON → `/add-bank-parser`).
- When `parserVersion` increases, these rows are re-parsed automatically.

## Testing (pure JVM)
- Fixtures live in `app/src/test/resources/sms/<bank>/*.json`, in this format: `{id, sender, body, sentAt, origin: real|synthetic, expected: {...}}`. One parameterized golden test runs all of them.
- Meta-tests:
  - Every template has at least one fixture.
  - Every `verified` template has at least one **real** fixture.
- Negative fixtures:
  - the HDFC OTP sample
  - promos
  - failed transactions
  - mandate reminders
- Chain test: the 6 consecutive Indian Bank messages.
- Fuzz test: random, truncated and mutated inputs, including 10k-character input. Every case must **never throw** and must finish in under 50 ms, which guards against regex backtracking.
- Regression: each user correction of a parse can be exported as a fixture.

## Rejected
- **The 7-extractor class pipeline as the primary strategy.** Fields get confused, for example the Avl Bal figure being read as the amount.
- **Templates only.** A wording change would drop money silently. The gate and fallback prevent that.
- **On-device ML or an LLM.** Not deterministic, so it can't be golden-tested.
