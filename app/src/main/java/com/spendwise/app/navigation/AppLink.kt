package com.spendwise.app.navigation

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app destinations a notification can open. Carries IDs only — never amounts or text. */
sealed interface AppLink {
    /** The review list of ONE account (the one the notification was about). */
    data class Review(val accountId: Long) : AppLink
    data class Transaction(val txnId: Long) : AppLink

    fun writeTo(intent: Intent) {
        when (this) {
            is Review -> intent.putExtra(EXTRA_LINK, LINK_REVIEW).putExtra(EXTRA_ID, accountId)
            is Transaction -> intent.putExtra(EXTRA_LINK, LINK_TXN).putExtra(EXTRA_ID, txnId)
        }
    }

    companion object {
        private const val EXTRA_LINK = "spendwise.link"
        private const val EXTRA_ID = "spendwise.id"
        private const val LINK_REVIEW = "review"
        private const val LINK_TXN = "txn"

        fun from(intent: Intent?): AppLink? = when (intent?.getStringExtra(EXTRA_LINK)) {
            LINK_REVIEW -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let(::Review)
            LINK_TXN -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let(::Transaction)
            else -> null
        }
    }
}

/** One pending link, handed from MainActivity to the nav host and consumed once. */
object AppLinks {
    private val _pending = MutableStateFlow<AppLink?>(null)
    val pending: StateFlow<AppLink?> = _pending.asStateFlow()

    fun offer(link: AppLink?) {
        if (link != null) _pending.value = link
    }

    fun consume() {
        _pending.value = null
    }
}
