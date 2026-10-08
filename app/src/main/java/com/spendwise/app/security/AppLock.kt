package com.spendwise.app.security

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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

/** Lifecycle state kept separate from Android so timeout and fail-closed transitions are unit-testable. */
internal class AppLockSession {
    var locked: Boolean? = null
        private set
    private var leftAtElapsed: Long? = null
    private var lockEnabled: Boolean? = null

    /** Cover an unlocked screen synchronously before Activity/Compose collectors resume. */
    fun prepareForStart() {
        if (lockEnabled != false && locked != true) locked = null
    }

    fun onStart(enabled: Boolean, timeoutMs: Long, nowElapsed: Long) {
        lockEnabled = enabled
        if (locked != true) locked = AppLockPolicy.shouldLock(enabled, leftAtElapsed, nowElapsed, timeoutMs)
    }

    fun onStop(nowElapsed: Long, prompting: Boolean) {
        if (prompting) return
        leftAtElapsed = nowElapsed
        // Leave the old UI covered until the resume decision is complete. An already locked state stays latched.
        if (lockEnabled != false && locked != true) locked = null
    }

    fun setLockEnabled(enabled: Boolean) {
        lockEnabled = enabled
    }

    fun unlocked() {
        locked = false
        leftAtElapsed = null
    }
}

/**
 * App lock (ADR-002 rule 9): a UI gate only — it never guards a key the database depends on, so a changed fingerprint
 * can never lock anyone out of their data. Fingerprint / face, or the phone's PIN as a fallback.
 */
@Singleton
class AppLock @Inject constructor(
    private val prefs: UserPreferencesRepository,
) {
    /** null = not decided yet (content stays hidden until it is). */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    private val session = AppLockSession()
    private var prompting = false

    /** Must run before super.onStart() so Compose cannot draw the previous unlocked frame. */
    fun prepareForStart() {
        if (prompting) return
        session.prepareForStart()
        _locked.value = session.locked
    }

    suspend fun onStart() {
        if (prompting || session.locked == true) return
        val enabled = prefs.appLockEnabled.first()
        val timeout = prefs.appLockTimeoutSec.first() * 1_000L
        session.onStart(enabled, timeout, SystemClock.elapsedRealtime())
        _locked.value = session.locked
    }

    fun onStop() {
        session.onStop(SystemClock.elapsedRealtime(), prompting)
        _locked.value = session.locked
    }

    /** Keep the synchronous lifecycle cache in step with the settings screen. */
    fun setLockEnabled(enabled: Boolean) = session.setLockEnabled(enabled)

    fun unlocked() {
        session.unlocked()
        _locked.value = false
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
