# Development phases

Each phase ends with a passing test suite, an auditor run where relevant, and one commit (`feat: …`). The user tries the build on the phone at the end of phases 3, 4, 5, 6 and 8.

| # | Phase | Councils | Done when |
|---|---|---|---|
| 1 | Architecture | Architecture, Schema, Transaction model, SMS pipeline, Parser design, Design system | ADRs accepted; schema and lifecycle documented |
| 2 | Foundation | — | Project builds and installs; theme, navigation shell and Room are wired up |
| 3 | Accounts | — | Create/edit/switch accounts, balance + adjustments + reconcile, isolation tests pass |
| 4 | Transactions | — | Manual entry, categories, transfers, cash, refunds, splits; integrity tests pass |
| 5 | SMS | — | Receiver, parsers, unknown-account flow, dedup, pending review, historical import, pull-to-refresh |
| 6 | Analytics | — | Summary, categories, trends, merchants, insights, budgets, recurring; every calculation tested |
| 7 | Security | — | Biometric lock, ADR-002 hardening, encrypted backup/restore, CSV export |
| 8 | UI polish | — | Design review: glass, motion, empty/error/loading states, light theme, accessibility |
| 9 | Testing | — | Unit, parser, Room, integration, Compose UI, isolation, performance (10k transactions) |
| 10 | Final review | Final review | Justified fixes from the review are implemented |
