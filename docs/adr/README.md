# Architecture Decision Records

Each major decision is made through `/council` and recorded here. A superseded ADR is kept and marked as superseded, never deleted.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-category-scope.md) | Global category catalogue + per-account overlay; two-level merchant rules | Accepted (rule-learning default pending user) |
| [002](ADR-002-at-rest-encryption.md) | No DB-level encryption in V1; mandatory leak hardening; passphrase-encrypted backups | Accepted |

## Planned councils
- Overall architecture (layers, DI, navigation, state)
- Database schema (full entity model, indexes, dedup constraints)
- Transaction model (lifecycle, transfers, refunds, splits, adjustments)
- SMS pipeline (receiver, inbox reads, watermark, dedup, transfer pairing)
- Parser design (generic + per-bank, confidence scoring)
- Design system (glass implementation, theming, motion)
- Final product & engineering review
