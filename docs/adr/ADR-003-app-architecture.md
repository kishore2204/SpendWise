# ADR-003: App architecture
- Status: Accepted
- Date: 2026-10-07
- Council (Core): Android Architect, Security & Data, Devil's Advocate, Simplicity Champion, Performance Optimizer

## Decision
**Layers.** UI (Compose) → ViewModel (`StateFlow<UiState>`) → Repository → Room DAO.
- There's no use-case layer for reads.
- Multi-table writes go through a few **ledger commands** in `domain/`, each running inside one `db.withTransaction {}`: `ProcessIncoming`, `ConfirmPending`, `CreateTransfer`, `SetSplits`, `LinkRefund`, `SetBalanceAnchor`. Money invariants are written only here.

**DI.** Hilt (KSP), with `@HiltWorker` for WorkManager. The vote was 3–2 against manual DI: workers, receivers and ViewModels make manual wiring awkward, and Hilt's compile-time graph adds less than 5 ms at startup.

**Navigation.** Navigation 3: `@Serializable` `NavKey` routes and `rememberNavBackStack`, which survives process death. If Nav3 blocks a pattern we need, fall back to type-safe Navigation Compose.

**Selected account.** A hybrid, shaped by the Devil's Advocate's challenge:
- `SelectedAccountStore` (`@Singleton`, backed by DataStore) exposes `StateFlow<AccountId?>`. It's the source of the account for the top-level tabs.
  - If the stored ID is archived or missing, fall back to the first active account.
  - With 0 accounts it's `null`, and screens show "Add account".
- ViewModels use `selected.distinctUntilChanged().flatMapLatest { repo.observeX(it) }.stateIn(viewModelScope, WhileSubscribed(5_000), Loading)`.
- **Every `UiState` carries the `accountId` it was computed for.** The UI shows a skeleton when that doesn't match the selected account, so one account's numbers never show under another account's header.
- **Detail routes carry an explicit `accountId`.** A notification deep link for account B switches the selection to B first.

**Ingestion.** Receiver, pull-to-refresh and historical import only *stage* raw messages. One serialized processor parses and writes them (ADR-005).

**Packages** (single `app` module):
```
com.spendwise.app
├── core/        money (Paise, INR formatting), time (IST), db factory seam
├── data/        db (entities, DAOs, views, triggers, migrations), repository/
├── domain/      ledger commands
├── transaction/ detection/, parsing/, bankparsers/{generic,indianbank,hdfc}/
├── feature/     home, transactions, review, accounts, insights, budgets, recurring, settings, onboarding
├── ui/          theme + design system (ADR-007)
└── di/
```

## Rejected
- **Manual DI:** viable, but awkward for workers and receivers.
- **Koin:** errors show up at runtime.
- **Read use-cases:** ceremony without a benefit.
- **Route-only account scoping:** it loses the persisted last-used account. Its safety benefit is kept by stamping every `UiState` with its accountId and by detail routes carrying an explicit account.

## Revisit if
Navigation 3 blocks a needed transition or deep-link pattern.
