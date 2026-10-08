# Architecture Decision Records

Each major decision is made through `/council` and recorded here. A superseded ADR is kept and marked as superseded, never deleted.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-category-scope.md) | Global category catalogue + per-account overlay; two-level merchant rules | Accepted |
| [002](ADR-002-at-rest-encryption.md) | No DB-level encryption in V1; mandatory leak hardening; passphrase-encrypted backups | Accepted |
| [003](ADR-003-app-architecture.md) | Layers + ledger commands, Hilt, Navigation 3, selected-account store with accountId-stamped UiState | Accepted |
| [004](ADR-004-transaction-model-and-schema.md) | Signed legs + kind trigger, `v_confirmed_txn`, transfer legs, **balance anchors**, schema, 3-layer dedup | Accepted |
| [005](ADR-005-sms-pipeline.md) | Receiver stages → single processor; fingerprint; 72h-overlap refresh; financial gate; no INTERNET permission | Accepted |
| [006](ADR-006-parser-design.md) | Hybrid per-bank templates + helpers; rule-table confidence; Couldn't-read list; fixture/fuzz tests | Accepted |
| [007](ADR-007-design-system.md) | "Light-glass": live blur only where content scrolls beneath; tokens, type, calm motion | Partly superseded by 008 (glass) |
| [008](ADR-008-liquid-glass.md) | iOS-style liquid glass: Kyant backdrop, aurora, two backdrops, liquid tab bar with droplet | Revised by 009 |
| [009](ADR-009-calm-glass-and-palette.md) | Calm glass: neutral background, minimal 3-hue palette (solid cards revised by 010) | Accepted |
| [010](ADR-010-guilloche-pattern.md) | Monochrome guilloché pattern behind glass; cards glass again; pattern cached as bitmap | Accepted (user decision) |
| [011](ADR-011-shared-expenses.md) | Shared expenses: friend shares + settlements + `REPAYMENT` kind; own spending excludes shares | Accepted (total-per-friend open) |

## Planned councils
- Final product & engineering review (Phase 10)
