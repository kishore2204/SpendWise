package com.spendwise.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.spendwise.app.core.money.Money
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import androidx.compose.foundation.shape.RoundedCornerShape

/** Inputs are never glass (ADR-011 audit): a calm, clearly bounded field on the card. */
@Composable
private fun fieldColors() = SpendWise.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.primary,
        unfocusedBorderColor = c.glassHairline,
        focusedContainerColor = c.surface3.copy(alpha = 0.6f),
        unfocusedContainerColor = c.surface3.copy(alpha = 0.6f),
        focusedLabelColor = c.primary,
        unfocusedLabelColor = c.text2,
        cursorColor = c.primary,
        focusedTextColor = c.text1,
        unfocusedTextColor = c.text1,
        errorBorderColor = c.danger,
        errorLabelColor = c.danger,
        errorSupportingTextColor = c.danger,
        focusedPrefixColor = c.text2,
        unfocusedPrefixColor = c.text2,
    )
}

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
        shape = RoundedCornerShape(Radius.md),
        colors = fieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Rupee amount entry. Accepts "2,844.44", "-500" etc.; validated with [parseSignedRupees], never via Double. */
@Composable
fun MoneyInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            onValueChange(new.replace('\u2212', '-').filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(16))
        },
        label = { Text(label) },
        prefix = { Text("₹ ") },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(Radius.md),
        colors = fieldColors(),
        textStyle = SpendWise.money.row,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * "-1,250.50" → -125050. Accepts the ASCII "-" and the true minus "−". Commas are allowed only in real grouping
 * positions (Indian 1,23,456 or Western 123,456) — "2844,44" is rejected rather than read as ₹2,84,444.
 * Null when not an unambiguous amount; empty → 0 only when [emptyIsZero].
 */
fun parseSignedRupees(text: String, emptyIsZero: Boolean = false): Long? {
    val t = text.trim().replace('\u2212', '-')
    if (t.isEmpty()) return if (emptyIsZero) 0L else null
    val negative = t.startsWith("-")
    val body = t.removePrefix("-")
    if (',' in body) {
        val whole = body.substringBefore('.')
        val indian = Regex("^\\d{1,2}(,\\d{2})*,\\d{3}$")
        val western = Regex("^\\d{1,3}(,\\d{3})+$")
        if (!indian.matches(whole) && !western.matches(whole)) return null
    }
    val paise = Money.parseRupees(body) ?: return null
    return if (negative) -paise else paise
}
