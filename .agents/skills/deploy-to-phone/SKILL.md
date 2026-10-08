---
name: deploy-to-phone
description: Build SpendWise and install it on the user's Samsung over adb, reporting build/install errors clearly. Use when the user wants to try the app on their phone.
argument-hint: "[debug|release] [--launch]"
---

# /deploy-to-phone [debug|release] [--launch]

Default variant: **debug**, for development. Use **release** for the copy the user relies on day to day (see ADR-002: a debuggable build lets `adb run-as` copy the database off the phone).

The two variants install side by side: debug uses the `.debug` applicationId suffix, so test data never mixes with real data.

## Steps

1. **Use the Android Studio JDK.** The system `java` is JDK 22.
   ```bash
   export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   ```
2. **Check for a device.** Run `adb devices`. If no device shows as `device`:
   - `unauthorized`: ask the user to accept the RSA prompt on the phone.
   - Empty list: ask the user to (a) enable Developer options → USB debugging, (b) turn off **Auto Blocker** (Settings → Security and privacy → Auto Blocker), which blocks USB commands and app installs on One UI, and (c) choose "File transfer" USB mode. Stop here until a device is listed.
3. **Build.**
   ```bash
   ./gradlew assembleDebug     # or assembleRelease
   ```
   On failure, show the first real error (not the whole Gradle log) and fix it if it's in our code.
   Release builds need `keystore.properties` in the project root (gitignored). If it's missing, tell the user. Never create or commit signing secrets on their behalf.
4. **Install.**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the signature changed. **Don't uninstall automatically**, because that deletes the user's data. Explain this and ask.
5. **Launch (if `--launch`).**
   ```bash
   adb shell monkey -p com.spendwise.app.debug -c android.intent.category.LAUNCHER 1
   ```
6. **First-install reminders** (only the first time a build with SMS or notification access is installed): for sideloaded apps, Android 16 hides SMS and notification-listener permissions behind *App info → ⋮ → Allow restricted settings*. Also add SpendWise to *Settings → Battery → Background usage limits → Never sleeping apps*.

Report: variant, APK size, device model (`adb shell getprop ro.product.model`), and whether the install succeeded.
