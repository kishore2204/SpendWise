# Indian Bank: SMS formats

The samples are anonymized: the account digits, person name and RRNs are fake, and the structure and punctuation are exact. The source is the user's screenshot from 2026-10-07.

## Sender
`VM-INDBNK-S`. Match on the `INDBNK` part. The 2-letter prefix (`VM`, `AD`, `JD`…) varies by operator/circle. The `-S` suffix marks a service message.

## Template: UPI debit ("Sent")

```
Sent Rs.<amount> from A/c *<last4> on <dd-MM-yy> to <payee>.RRN <12-digit RRN>.Avl Bal Rs.<balance>.Not you?SMS BLOCK to 9289592895-Indian Bank
```

| Field | Source | Notes |
|---|---|---|
| Direction | `Sent` | Debit |
| Payment method | Implied by `RRN` (12-digit UPI reference) | UPI |
| Amount | `Rs.23.00` | No thousands separators in the samples. Still accept commas. Parse to paise. |
| Account | `A/c *0000` | Last 4 digits |
| Date | `07-10-26` | `dd-MM-yy`, Asia/Kolkata. **No time.** Take the time from the SMS timestamp. |
| Payee | Text between `to ` and `.RRN` | **Don't split on the first `.`.** A person's initial (`Mr Ravi K.RRN`) puts a dot right before `RRN`. |
| Reference | `RRN 600000000001` | The strongest dedup key, unique per UPI transaction. |
| Balance | `Avl Bal Rs.2844.44` | Feed into reconciliation suggestions and the confidence check. |

### Samples
```
sender: VM-INDBNK-S
Sent Rs.23.00 from A/c *0000 on 07-10-26 to CUMTA.RRN 600000000001.Avl Bal Rs.2844.44.Not you?SMS BLOCK to 9289592895-Indian Bank

sender: VM-INDBNK-S
Sent Rs.100.00 from A/c *0000 on 07-10-26 to Mr Ravi K.RRN 600000000002.Avl Bal Rs.2744.44.Not you?SMS BLOCK to 9289592895-Indian Bank

sender: VM-INDBNK-S
Sent Rs.20.00 from A/c *0000 on 07-10-26 to Mr Ravi K.RRN 600000000003.Avl Bal Rs.2724.44.Not you?SMS BLOCK to 9289592895-Indian Bank

sender: VM-INDBNK-S
Sent Rs.121.00 from A/c *0000 on 07-10-26 to HungerBox.RRN 600000000004.Avl Bal Rs.2603.44.Not you?SMS BLOCK to 9289592895-Indian Bank

sender: VM-INDBNK-S
Sent Rs.15.00 from A/c *0000 on 07-10-26 to CUMTA.RRN 600000000005.Avl Bal Rs.2588.44.Not you?SMS BLOCK to 9289592895-Indian Bank

sender: VM-INDBNK-S
Sent Rs.20.00 from A/c *0000 on 07-10-26 to Lakshmi Store.RRN 600000000006.Avl Bal Rs.2568.44.Not you?SMS BLOCK to 9289592895-Indian Bank
```

## Observations that shape the design
- **Balance chain.** Each message's `Avl Bal` equals the previous balance minus the amount (2844.44 → 2744.44 → 2724.44 → 2603.44 → 2588.44 → 2568.44). The parser and ingestion can use this to:
  - raise confidence when the chain holds,
  - detect a **missed SMS** when it breaks,
  - suggest a reconciliation adjustment.
- **Person vs merchant.** `Mr Ravi K` is a P2P payment, and the `Mr`/`Ms`/`Mrs` prefix signals it. P2P payments should default to "needs category" rather than a guessed category.
- **`CUMTA` and `HungerBox`** are canteen/food vendors, so they're good seeds for the default merchant dictionary (Food).

## Still needed
Credit/received, ATM withdrawal, debit card POS, NEFT/IMPS in and out, refund/reversal, and non-transaction noise (OTP, balance enquiry, promos). Also samples for HDFC, SBI and ICICI.
