# SpendWise

**A private, local-first expense tracker for Android.** SpendWise is designed for one person using a Samsung phone, with separate ledgers for each bank account and a Cash wallet.

> **Project status:** Phases 1–7 are implemented. Phase 7's unit tests pass. UI polish, broader device testing, and the final engineering review remain on the roadmap. See [Development phases](docs/development/phases.md).

## What it does

- Tracks expenses, income, transfers, refunds, split purchases, and shared expenses.
- Keeps balances, budgets, recurring items, search, and analytics scoped to the selected account. There is no All Accounts analytics view.
- Supports manual entry and optional bank-SMS detection. Detected transactions are reviewed before they affect balances.
- Learns merchant categories from corrections. Rules can be account-specific or shared across accounts.
- Includes account balance reconciliation, friend repayments, encrypted backup and restore, CSV export, and an optional biometric app lock.
- Offers dark, light, and system themes with a restrained Liquid Glass-inspired Android interface.

## Privacy and financial-data rules

SpendWise is built to keep financial data on the device:

- The Android manifest does **not** request `INTERNET`; the app has no backend or cloud sync.
- Android backup and device-transfer are disabled. The app does not continuously scan the SMS inbox: inbox reads occur only for an import the user starts or a pull-to-refresh.
- SMS parsing creates pending items for review. Pending and ignored transactions do not affect balances or analytics. Unknown accounts are shown for the user to approve or dismiss.
- The database uses Android's app sandbox and device file-based encryption; it is **not SQLCipher-encrypted**. The biometric lock is a screen gate, not a database-encryption key.
- User-created backup files are encrypted with a passphrase and can be restored on another phone. CSV is a separate, opt-in **unencrypted** export; treat it like a financial document.
- SMS text, account numbers, and financial details must not be logged or sent to external services. Test fixtures use anonymized examples.

For the full rationale and security requirements, see [ADR-002](docs/adr/ADR-002-at-rest-encryption.md) and [AGENTS.md](AGENTS.md).

## Account isolation

Each account is an independent financial space. Every money query and export uses one explicit `accountId`; balances and analytics are never combined across accounts.

Transactions store exact integer **paise** (`Long`), not floating-point values. Confirmed transfers have linked debit and credit legs and are excluded from income and expense. Refunds link to their original purchase, and split amounts must add up to the parent transaction. Balances are derived from confirmed transactions and append-only opening, manual, or reconciliation anchors, so importing older history does not rewrite today's balance.

## SMS handling

SMS access is optional and permission-gated. SpendWise supports event-driven handling of new messages, a user-started historical import, and pull-to-refresh for missed messages. Messages from allowlisted bank senders pass through a financial-message gate, bank-specific or generic parsers, and duplicate checks. Unrecognized financial-looking messages go to review instead of being silently finalized.

Parser coverage depends on bank message formats. Indian Bank and HDFC have anonymized sample fixtures in `docs/sms/samples/`; other formats may need review and more samples. Debug builds do not request SMS access and use a shell-only fake-data seeder for testing.

## Technology

- Kotlin, Coroutines, Flow, and StateFlow
- Jetpack Compose and Material 3
- Room / SQLite, with migrations and integrity rules
- Hilt and WorkManager for deferred processing
- AndroidX Biometric for the optional app lock
- Vico for Compose charts
- Gradle Kotlin DSL; a single Android `app` module

## Project layout

```text
app/src/main/java/com/spendwise/app/
├── core/            # Money, India time, merchant utilities
├── data/            # Room database, DAOs, account and preferences stores
├── domain/          # Account, transaction, category, shared-expense and analytics rules
├── feature/         # Home, accounts, transactions, SMS, insights and settings screens
├── navigation/      # App routes and deep links
├── security/        # Biometric lock, encrypted backup/restore and CSV export
├── transaction/     # SMS parsing, ingestion, deduplication and notifications
└── ui/              # Compose components, glass surfaces and theme

docs/
├── adr/             # Architecture decision records and implementation notes
├── development/     # Phased development plan
├── sms/samples/     # Anonymized bank message examples
└── ui/              # Design references
```

## Build and test

### Requirements

- Windows with Android Studio and its bundled **JBR 17**
- Android SDK packages required by the project, including Android API 37.2
- A connected Android device or emulator for installation/device checks

In PowerShell, set Gradle to use Android Studio's bundled JBR, then run the tests:

```powershell
$env:JAVA_HOME = "C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat :app:testDebugUnitTest
```

Build the development APK:

```powershell
./gradlew.bat :app:assembleDebug
```

The debug application ID is `com.spendwise.app.debug`, so it installs alongside the daily-use release app. **Keep debug data fictional.** Never grant SMS access to a debug build or put real account data in its database.

Install the debug APK on a connected device:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

Release builds are signed with a key that you create and control. Put the key and `keystore.properties` in the project root; both are gitignored. Never commit or share them. A properties file has this shape (replace each placeholder locally):

```properties
storeFile=spendwise-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=spendwise
keyPassword=YOUR_KEY_PASSWORD
```

Then build with:

```powershell
./gradlew.bat :app:assembleRelease
```

Without the local signing properties, Gradle produces an unsigned release APK. Keep the signing key backed up securely: losing it prevents signing future updates with the same app identity.

## Development workflow

The app is developed in phases, with unit tests and architecture decision records for important choices. Read [AGENTS.md](AGENTS.md) for non-negotiable engineering rules, [CLAUDE.md](CLAUDE.md) for Claude Code project guidance, and [the ADR index](docs/adr/README.md) for decision history.

Current roadmap:

1. Architecture — documented
2. Foundation — implemented
3. Accounts — implemented
4. Transactions — implemented
5. SMS — implemented
6. Account-specific analytics — implemented
7. Security, backups, and exports — implemented; device validation remains
8. UI polish — planned
9. Broader testing and performance checks — planned
10. Final product and engineering review — planned

This is a personal sideloaded Android project, not a banking product. SMS detection depends on Android permissions and bank message formats; review imported or detected entries before relying on them for reconciliation.
