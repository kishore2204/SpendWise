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
            SELECT RAISE(ABORT, 'txn: refund must be in the same account with the opposite sign')
                WHERE NEW.refundOfId IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM txn o
                    WHERE o.id = NEW.refundOfId
                      AND o.accountId = NEW.accountId
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

    val TRIGGERS: List<String> = listOf(
        txnChecks("INSERT"),
        txnChecks("UPDATE"),
        transferChecks("INSERT"),
        transferChecks("UPDATE"),
    )

    fun createTriggers(db: SupportSQLiteDatabase) {
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
        SeedCategory(50, "Salary", "INCOME", "work", 9),
        SeedCategory(51, "Interest", "INCOME", "percent", 10),
        SeedCategory(52, "Cashback & Rewards", "INCOME", "card_giftcard", 11),
        SeedCategory(53, "Other income", "INCOME", "add_circle", 0),
    )

    fun seed(db: SupportSQLiteDatabase) {
        SEED.forEachIndexed { index, c ->
            db.execSQL(
                "INSERT OR IGNORE INTO category (id, name, kind, iconKey, colorIndex, isSystem, sortOrder, archivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                arrayOf<Any>(c.id, c.name, c.kind, c.icon, c.color, if (c.system) 1 else 0, index),
            )
        }
    }
}
