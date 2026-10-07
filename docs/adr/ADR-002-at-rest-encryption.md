# ADR-002: At-rest database encryption and data-leak hardening
- Status: Accepted
- Date: 2026-10-07
- Council: Android Architect, Security & Data Engineer, Product/UX Architect, Devil's Advocate, Simplicity Champion, Performance Optimizer

## Context
Financial data stays on the device. Should the Room database itself be encrypted?

## Options considered
1. SQLCipher for Room, with a random DB key wrapped by an Android Keystore key.
2. No DB-level encryption. Rely on file-based encryption (FBE), the app sandbox and the biometric lock, plus hardening.
3. Field-level encryption of sensitive columns (raw SMS body, notes).

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | 2 + hardening, keep a seam for SQLCipher | Medium-high |
| Security & Data | 2 + hardening, keep a seam for SQLCipher | Medium |
| Product/UX | 2, data loss is worse than data exposure | Medium-high |
| Devil's Advocate | 2, and hardening is the real control | Medium-high |
| Simplicity | 2 | Medium-high |
| Performance | 1 (raw key, sqlcipher-android ≥ 4.6.1, about 5–15% query cost) | Medium |

## Decision
**Option 2, with mandatory hardening, and a seam so SQLCipher can be added later.**

## Why
The deciding argument came from the Security seat and the Devil's Advocate. The SMS receiver, the notification listener and WorkManager must all write while the phone is locked, so the DB key **cannot** require user authentication. A key without that requirement can be unwrapped by any code running as our app. So SQLCipher would only protect against copying the raw file off the device, and FBE plus Knox already covers a lost or locked phone.

A key bound to biometrics would also be invalidated when fingerprints are re-enrolled, which would make all financial history unreadable. A security feature must not create a data-loss risk.

Option 3 breaks search and re-parsing for little gain: the same SMS text already sits in plaintext in Samsung Messages' database.

The Performance seat's SQLCipher setup (raw key, 16KB-ready 4.6.1+) is sound and is the plan if we revisit this decision.

## Consequences / implementation rules (the real controls)
1. Set `android:allowBackup="false"` **and** `dataExtractionRules` that exclude every domain for both cloud backup and **device-transfer**. On Android 12+, `allowBackup=false` alone does not block device-to-device transfer, including Samsung Smart Switch.
2. The build used day to day is **release, `debuggable=false`**. A sideloaded debug build lets `adb run-as` copy the DB. Debug builds use the `.debug` applicationId suffix and test data only.
3. The SMS receiver is protected by `android.permission.BROADCAST_SMS`. The notification listener requires `BIND_NOTIFICATION_LISTENER_SERVICE`. No other component is exported.
4. Set `FLAG_SECURE` on windows that show financial data, so it stays out of screenshots and the recents screen.
5. Never log SMS bodies, amounts or account numbers. Release builds strip debug logging.
6. Notifications use `VISIBILITY_PRIVATE` with a redacted public version: no amounts on the lock screen.
7. Backups and exports are encrypted with a **user passphrase**: PBKDF2-HMAC-SHA256 with a high iteration count (or Argon2id if a vetted library is adopted) and AES-256-GCM. The file has a versioned header with the schema version and an integrity check, and is written through the Storage Access Framework. **Backups never depend on Keystore**, so they can be restored on a new phone. A plain CSV export is opt-in and shows a clear warning.
8. Room is built through a single `SupportSQLiteOpenHelper.Factory` seam, so SQLCipher can be added with a one-time `sqlcipher_export` migration.
9. The biometric lock is a UI gate only. It never guards a key the database depends on.

## Revisit if
- The phone is rooted, shared with other people, or forensic extraction while unlocked becomes part of the threat model.
- Cloud sync is ever added.

If so, adopt option 1 as described by the Performance seat: a random 32-byte key, wrapped by a Keystore key **without** a user-authentication requirement, passed to SQLCipher as raw key bytes.
