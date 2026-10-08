package com.spendwise.app.security

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spendwise.app.feature.settings.LockGate
import com.spendwise.app.ui.theme.SpendWiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockGateAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `locked screen exposes only the lock action to accessibility`() {
        compose.setContent {
            SpendWiseTheme(darkTheme = true) {
                LockGate(locked = true, onUnlock = {}) {
                    Column {
                        Text("HDFC current balance")
                        Button(onClick = {}) { Text("Hidden transaction action") }
                    }
                }
            }
        }

        compose.onNodeWithText("HDFC current balance").assertDoesNotExist()
        compose.onNodeWithText("Hidden transaction action").assertDoesNotExist()
        compose.onNodeWithText("SpendWise is locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun `accessibility actions return when the gate opens`() {
        compose.setContent {
            SpendWiseTheme(darkTheme = true) {
                val count = remember { mutableIntStateOf(0) }
                LockGate(locked = false, onUnlock = {}) {
                    Button(onClick = { count.intValue++ }) { Text("Confirm transaction") }
                    Text("Confirmed actions: ${count.intValue}")
                }
            }
        }

        compose.onNodeWithText("Confirm transaction").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Confirmed actions: 1").assertIsDisplayed()
    }
}
