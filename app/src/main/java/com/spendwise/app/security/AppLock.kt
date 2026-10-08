package com.spendwise.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Pure rule, unit-tested: lock on a cold start, or after being away at least the chosen time. */
object AppLockPolicy {
    fun shouldLock(enabled: Boolean, leftAt: Long?, now: Long, timeoutMs: Long): Boolean = when {
        !enabled -> false
        leftAt == null -> true // cold start
        else -> now - leftAt >= timeoutMs
    }
}

/**
 * App lock (ADR-002 rule 9): a UI gate only — it never guards a key the database depends on, so a changed fingerprint
 * can never lock anyone out of their data. Fingerprint / face, or the phone's PIN as a fallback.
 */
@Singleton
class AppLock @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) {
    /** null = not decided yet (content stays hidden until it is). */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    private var leftAt: Long? = null
    private var expectingExternal = false
    private var prompting = false

    suspend fun onStart() {
        if (prompting) return
        if (expectingExternal) {
            // Back from a file picker / settings screen we opened ourselves: not "leaving the app".
            expectingExternal = false
            if (_locked.value == null) _locked.value = prefs.appLockEnabled.first()
            return
        }
        val enabled = prefs.appLockEnabled.first()
        val timeout = prefs.appLockTimeoutSec.first() * 1_000L
        if (_locked.value != true) _locked.value = AppLockPolicy.shouldLock(enabled, leftAt, clock.now(), timeout)
    }

    fun onStop() {
        if (prompting || expectingExternal) return
        leftAt = clock.now()
    }

    /** Call right before launching a system screen (file picker, app settings) so returning doesn't lock. */
    fun expectExternal() {
        expectingExternal = true
    }

    fun unlocked() {
        _locked.value = false
        leftAt = null
    }

    /** Shows the system prompt; [onResult] true when the user proved it's them. */
    fun prompt(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        prompting = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    onResult(false)
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Fingerprint, face or your phone's PIN")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /** True when the phone has a screen lock / biometrics the prompt can use. */
        fun available(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
