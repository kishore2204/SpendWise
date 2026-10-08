package com.spendwise.app

import android.content.Intent
import android.os.Bundle
import com.spendwise.app.navigation.AppLink
import com.spendwise.app.navigation.AppLinks
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.spendwise.app.feature.settings.LockOverlay
import com.spendwise.app.security.AppLock
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendwise.app.navigation.SpendWiseNavHost
import com.spendwise.app.ui.theme.SpendWiseTheme
import com.spendwise.app.ui.theme.isDarkFor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ADR-002: keep financial data out of screenshots and the recents thumbnail. Debug builds hold only
        // test data (separate .debug app), so they stay capturable for UI verification over adb.
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState == null) AppLinks.offer(AppLink.from(intent))

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = themeMode.isDarkFor()

            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }

            SpendWiseTheme(darkTheme = dark) {
                val locked by appLock.locked.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    SpendWiseNavHost()
                    // App lock (ADR-002 rule 9): a solid cover until unlocked — and until the lock state is known.
                    if (locked != false) LockOverlay(locked, onUnlock = ::unlock)
                }
                LaunchedEffect(locked) { if (locked == true) unlock() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appLock.onStart() }
    }

    override fun onStop() {
        super.onStop()
        appLock.onStop()
    }

    private fun unlock() {
        // If the phone no longer has any screen lock, the gate can't prompt — never trap the user out of their data.
        if (!AppLock.available(this)) {
            appLock.unlocked()
            return
        }
        appLock.prompt(this, "Unlock SpendWise") { ok -> if (ok) appLock.unlocked() }
    }

    /** A notification tapped while the app is open (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLinks.offer(AppLink.from(intent))
    }
}
