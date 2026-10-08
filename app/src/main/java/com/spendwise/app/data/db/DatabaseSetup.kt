package com.spendwise.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.spendwise.app.data.db.entity.TxnKind

/**
 * SQL that Room annotations cannot express. Triggers are (re)created idempotently on every open, so a
 * migration can never silently drop an invariant (ADR-004).
 */
object DatabaseSetup {

    // Stable IDs for system categories; code may reference these.
    const val CATEGORY_TRANSFER = 1L
    const val CATEGORY_REFUND = 2L
    const val CATEGORY_ADJUSTMENT = 3L

    private fun kinds(predicate: (TxnKind) -> Boolean) =
        TxnKind.entries.filter(predicate).joinToString(",") { "'${it.name}'" }

    private val NEGATIVE_KINDS = kinds { it.direction == TxnKind.Direction.OUT }
    private val POSITIVE_KINDS = kinds { it.direction == TxnKind.Direction.IN }

    private fun txnChecks(event: String) = """
        CREATE TRIGGER IF NOT EXISTS trg_txn_check_$event BEFORE $event ON txn
        BEGIN
            SELECT RAISE(ABORT, 'txn: amount must not be zero') WHERE NEW.amountPaise = 0;
            SELECT RAISE(ABORT, 'txn: sign does not match kind')
                WHERE (NEW.kind IN ($NEGATIVE_KINDS) AND NEW.amountPaise > 0)
                   OR (NEW.kind IN ($POSITIVE_KINDS) AND NEW.amountPaise < 0);
            SELECT RAISE(ABORT, 'txn: isCredit must match sign')
                WHERE NEW.isCredit != (NEW.amountPaise > 0);
            SELECT RAISE(ABORT, 'txn: refund must be of spending in the same account with the opposite sign')
                WHERE NEW.refundOfId IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM txn o
                    WHERE o.id = NEW.refundOfId
                      AND o.accountId = NEW.accountId
                      AND o.kind IN ('EXPENSE','FEE')
                      AND (o.amountPaise > 0) != (NEW.amountPaise > 0)
                );
        END
    """.trimIndent()

    private fun transferChecks(event: String) = """
        CREATE TRIGGER IF NOT EXISTS trg_transfer_check_$event BEFORE $event ON transfer
        BEGIN
            SELECT RAISE(ABORT, 'transfer: legs must be in different accounts')
                WHERE (SELECT accountId FROM txn WHERE id = NEW.fromTxnId)
                    = (SELECT accountId FROM txn WHERE id = NEW.toTxnId);
            SELECT RAISE(ABORT, 'transfer: from leg must be negative, to leg positive, equal magnitude')
                WHERE (SELECT amountPaise FROM txn WHERE id = NEW.fromTxnId) >= 0
                   OR (SELECT amountPaise FROM txn WHERE id = NEW.toTxnId) <= 0
                   OR -(SELECT amountPaise FROM txn WHERE id = NEW.fromTxnId)
                       != (SELECT amountPaise FROM txn WHERE id = NEW.toTxnId);
        END
    """.trimIndent()

    // Balance history is append-only (ADR-004): edits and reconciles add anchors; nothing may rewrite or erase one.
    private const val ANCHOR_NO_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_anchor_no_update BEFORE UPDATE ON balance_anchor
        BEGIN SELECT RAISE(ABORT, 'balance_anchor is append-only'); END
    """
    private const val ANCHOR_NO_DELETE = """
        CREATE TRIGGER IF NOT EXISTS trg_anchor_no_delete BEFORE DELETE ON balance_anchor
        BEGIN SELECT RAISE(ABORT, 'balance_anchor is append-only'); END
    """

    private val TRIGGER_NAMES = listOf(
        "trg_txn_check_INSERT", "trg_txn_check_UPDATE", "trg_transfer_check_INSERT", "trg_transfer_check_UPDATE",
        "trg_anchor_no_update", "trg_anchor_no_delete",
    )

    val TRIGGERS: List<String> = listOf(
        txnChecks("INSERT"),
        txnChecks("UPDATE"),
        transferChecks("INSERT"),
        transferChecks("UPDATE"),
        ANCHOR_NO_UPDATE,
        ANCHOR_NO_DELETE,
    )

    /** Drops and recreates every trigger on each open, so a changed rule always replaces the installed one. */
    fun createTriggers(db: SupportSQLiteDatabase) {
        TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        TRIGGERS.forEach(db::execSQL)
    }

    private data class SeedCategory(val id: Long, val name: String, val kind: String, val icon: String, val color: Int, val system: Boolean = false)

    private val SEED = listOf(
        SeedCategory(CATEGORY_TRANSFER, "Transfer", "SYSTEM", "swap_horiz", 0, system = true),
        SeedCategory(CATEGORY_REFUND, "Refund", "SYSTEM", "undo", 1, system = true),
        SeedCategory(CATEGORY_ADJUSTMENT, "Adjustment", "SYSTEM", "tune", 2, system = true),
        SeedCategory(10, "Food & Dining", "EXPENSE", "restaurant", 3),
        SeedCategory(11, "Groceries", "EXPENSE", "local_grocery_store", 4),
        SeedCategory(12, "Shopping", "EXPENSE", "shopping_bag", 5),
        SeedCategory(13, "Bills & Utilities", "EXPENSE", "receipt_long", 6),
        SeedCategory(14, "Transport", "EXPENSE", "directions_car", 7),
        SeedCategory(15, "Fuel", "EXPENSE", "local_gas_station", 8),
        SeedCategory(16, "Entertainment", "EXPENSE", "movie", 9),
        SeedCategory(17, "Subscriptions", "EXPENSE", "autorenew", 10),
        SeedCategory(18, "Education", "EXPENSE", "school", 11),
        SeedCategory(19, "Health", "EXPENSE", "favorite", 0),
        SeedCategory(20, "Travel", "EXPENSE", "flight", 1),
        SeedCategory(21, "Household", "EXPENSE", "home", 2),
        SeedCategory(22, "Personal care", "EXPENSE", "spa", 3),
        SeedCategory(23, "Rent", "EXPENSE", "apartment", 4),
        SeedCategory(24, "EMI & Loans", "EXPENSE", "account_balance", 5),
        SeedCategory(25, "Insurance", "EXPENSE", "shield", 6),
        SeedCategory(26, "Gifts & Donations", "EXPENSE", "redeem", 7),
        SeedCategory(27, "Other", "EXPENSE", "more_horiz", 8),
        // Added 2026-10-08 with the merchant dictionary (seeded on open for existing installs).
        SeedCategory(28, "Clothing", "EXPENSE", "checkroom", 9),
        SeedCategory(29, "Footwear", "EXPENSE", "directions_walk", 10),
        SeedCategory(30, "Electronics", "EXPENSE", "devices", 11),
        SeedCategory(31, "Fitness", "EXPENSE", "fitness_center", 0),
        SeedCategory(32, "Jewellery", "EXPENSE", "diamond", 1),
        SeedCategory(33, "Investment", "EXPENSE", "trending_up", 2),
        SeedCategory(50, "Salary", "INCOME", "work", 9),
        SeedCategory(51, "Interest", "INCOME", "percent", 10),
        SeedCategory(52, "Cashback & Rewards", "INCOME", "card_giftcard", 11),
        SeedCategory(53, "Other income", "INCOME", "add_circle", 0),
    )

    /** "Other" always sorts last, however many categories are added later. */
    private const val LAST = 99

    fun seed(db: SupportSQLiteDatabase) {
        SEED.forEachIndexed { index, c ->
            db.execSQL(
                "INSERT OR IGNORE INTO category (id, name, kind, iconKey, colorIndex, isSystem, sortOrder, archivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                arrayOf<Any>(c.id, c.name, c.kind, c.icon, c.color, if (c.system) 1 else 0, if (c.id == 27L) LAST else index),
            )
        }
    }

    /**
     * Idempotent, runs on every open: categories added in an app update reach existing installs, and every account
     * sees them in its own overlay (ADR-001). Never touches a row that already exists (user choices are kept).
     */
    fun seedOnOpen(db: SupportSQLiteDatabase) {
        seed(db)
        db.execSQL("UPDATE category SET sortOrder = $LAST WHERE id = 27")
        db.execSQL("UPDATE account_category SET sortOrder = $LAST WHERE categoryId = 27")
        db.execSQL(
            """
            INSERT OR IGNORE INTO account_category (accountId, categoryId, hidden, sortOrder)
            SELECT a.id, c.id, 0, c.sortOrder FROM account a CROSS JOIN category c
            WHERE c.archivedAt IS NULL
              AND NOT EXISTS (SELECT 1 FROM account_category x WHERE x.accountId = a.id AND x.categoryId = c.id)
            """.trimIndent(),
        )
    }
}
