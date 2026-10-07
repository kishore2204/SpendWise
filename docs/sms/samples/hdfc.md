# HDFC Bank: SMS formats

The samples are anonymized: the account digits, person name, refs and OTP are fake, and the structure, line breaks and punctuation are exact. The source is the user's screenshot from 2026-10-07.

## Sender
`AD-HDFCBK-T`. Match on the `HDFCBK` part. The suffix `-T` means transactional and `-S` means service, so normalization must strip any single-letter DLT suffix (`-[SPTG]`), not only `-S`.

## Template: UPI debit ("Sent"), **verified with real samples**

The message is spread over several lines (newline-separated):
```
Sent Rs.<amount>
From HDFC Bank A/C *<last4>
To <payee>
On <dd/MM/yy>
Ref <12-digit RRN>
Not You?
Call 18002586161/SMS BLOCK UPI to 7308080808
```

| Field | Source | Notes |
|---|---|---|
| Direction | `Sent` | Debit |
| Payment method | `Ref` with 12 digits + "SMS BLOCK UPI" | UPI |
| Amount | `Rs.350.00` | No thousands separators in the samples (`Rs.4640.00`). Still accept commas. |
| Account | `A/C *1111` | Uppercase `A/C`. Indian Bank uses `A/c`, so match case-insensitively. |
| Payee | the `To ` line, up to the end of the line | **Truncated by the bank** at about 25 chars: `ANANTHAM RETAIL PRIVATE L`. |
| Date | `On 28/09/26` | `dd/MM/yy` with slashes. Indian Bank uses dashes. **No time.** |
| Reference | `Ref 600000000101` | The UPI RRN, used as the dedup key. |
| Balance | **none** | Unlike Indian Bank, HDFC UPI debits carry no available balance. **No balance-chain check is possible for this template.** |

### Samples
```
sender: AD-HDFCBK-T
Sent Rs.350.00
From HDFC Bank A/C *1111
To Mr A Kumar
On 28/09/26
Ref 600000000101
Not You?
Call 18002586161/SMS BLOCK UPI to 7308080808

sender: AD-HDFCBK-T
Sent Rs.4640.00
From HDFC Bank A/C *1111
To ANANTHAM RETAIL PRIVATE L
On 02/10/26
Ref 600000000102
Not You?
Call 18002586161/SMS BLOCK UPI to 7308080808
```

## Non-transaction: OTP (negative fixture)
```
sender: AD-HDFCBK-T
000000 is your OTP for service - Login request on HDFC Bank MyCards. Valid for 2 mins. Never share OTP @mycards.hdfc.bank.in #000000
```
This must be classified as NOT_FINANCIAL, and its body is **never stored**. It has no amount, so the financial gate rejects it. An explicit OTP reject pattern acts as a second guard.

## Observations that shape the design
- **Line breaks.** Templates must treat `\s+` (including `\n` and `\r\n`) as a separator. A body coming from the broadcast and from the inbox provider must normalize to the same fingerprint, so collapse whitespace before hashing.
- **Truncated merchant names.** The normalizer must strip *partial* corporate suffixes (`PRIVATE L`, `PRIVATE LI…`, `PVT LT`, `LIMITE`) before building `merchantKey`. Otherwise "ANANTHAM RETAIL PRIVATE L" and "ANANTHAM RETAIL PVT LTD" become different merchants.
- **A person as payee.** `Mr A Kumar` is P2P (honorific prefix). If the payee is the account holder's own name (an optional "your name" setting), suggest **"Transfer to your own account?"** instead of an expense, because UPI to self shows up exactly like this.
- **No balance.** HDFC confidence can't use the balance-chain booster. HIGH still requires a verified template, a trusted sender, amount, account and date.

## Still needed
Credit/received, debit card POS, ATM, NEFT/IMPS, refund/reversal, and credit card (if any). Until real samples exist, those HDFC templates are built from public patterns and marked `verified = false`, so they're capped at NEEDS_REVIEW.
