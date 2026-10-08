package com.spendwise.app.domain

import androidx.room.withTransaction
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountCategoryEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.CategoryKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-made categories (ADR-001): a category is a global LABEL; every account gets it in its own overlay. Names are
 * unique (case-insensitive) — typing an existing name reuses it (and brings an archived one back).
 */
@Singleton
class CategoryLedger @Inject constructor(private val db: SpendWiseDatabase) {

    /**
     * [forAccountId]: the account whose picker the name was typed in. If that account had hidden the category, it is
     * shown again there (the user just asked for it) — other accounts' hidden choices are left alone.
     */
    suspend fun createCategory(name: String, kind: CategoryKind, forAccountId: Long? = null): LedgerResult {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        if (clean.isEmpty()) return LedgerResult.Rejected("Type a name for the category")
        if (kind == CategoryKind.SYSTEM) return LedgerResult.Rejected("Not allowed")
        return db.withTransaction {
            val dao = db.categoryDao()
            val existing = dao.byName(clean)
            val id = when {
                existing == null -> dao.insert(
                    CategoryEntity(
                        id = maxOf(FIRST_CUSTOM_ID, dao.maxId() + 1),
                        name = clean,
                        kind = kind,
                        iconKey = "label",
                        colorIndex = (dao.count() % PALETTE).toInt(),
                        isSystem = false,
                        sortOrder = CUSTOM_SORT,
                    ),
                )
                existing.kind == CategoryKind.SYSTEM -> return@withTransaction LedgerResult.Rejected("\"${existing.name}\" is reserved")
                existing.kind != kind -> return@withTransaction LedgerResult.Rejected(
                    "\"${existing.name}\" is already an ${if (existing.kind == CategoryKind.INCOME) "income" else "spending"} category",
                )
                else -> {
                    if (existing.archivedAt != null) dao.unarchive(existing.id)
                    existing.id
                }
            }
            // Visible in every account's overlay (no-op where it already is; never un-hides elsewhere).
            db.accountCategoryDao().insertAll(
                db.accountDao().allIds().map { AccountCategoryEntity(accountId = it, categoryId = id, hidden = false, sortOrder = CUSTOM_SORT) },
            )
            if (forAccountId != null) db.accountCategoryDao().unhide(forAccountId, id)
            LedgerResult.Ok(id)
        }
    }

    private companion object {
        const val MAX_NAME = 24
        /** Built-in categories own ids below this, so a future built-in can never collide with a user's. */
        const val FIRST_CUSTOM_ID = 1_000L
        const val PALETTE = 12L
        /** After the built-in ones, before "Other" (99). */
        const val CUSTOM_SORT = 90
    }
}
