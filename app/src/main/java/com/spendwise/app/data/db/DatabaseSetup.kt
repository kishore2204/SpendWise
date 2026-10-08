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
    const val CATEGORY_REPAYMENT = 4L
    /** Budget target for "all spending" of an account (a system label; never on a transaction). */
    const val CATEGORY_ALL_SPENDING = 5L

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
            SELECT RAISE(ABORT, 'txn: refunds plus friends shares can not exceed the original')
                WHERE NEW.refundOfId IS NOT NULL AND NEW.status != 'IGNORED'
                  AND NEW.amountPaise
                    + COALESCE((SELECT SUM(r.amountPaise) FROM txn r WHERE r.refundOfId = NEW.refundOfId AND r.status != 'IGNORED' AND r.id IS NOT NEW.id), 0)
                    + COALESCE((SELECT SUM(s.amountPaise) FROM shared_share s WHERE s.txnId = NEW.refundOfId), 0)
                  > -(SELECT o.amountPaise FROM txn o WHERE o.id = NEW.refundOfId);
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

    // ---- Shared expenses (ADR-011)
    private fun shareChecks(event: String) = """
        CREATE TRIGGER IF NOT EXISTS trg_share_check_$event BEFORE $event ON shared_share
        BEGIN
            SELECT RAISE(ABORT, 'share: amount must be positive') WHERE NEW.amountPaise <= 0;
            SELECT RAISE(ABORT, 'share: only a confirmed expense can be shared')
                WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = NEW.txnId AND t.kind = 'EXPENSE' AND t.status = 'CONFIRMED');
            SELECT RAISE(ABORT, 'share: friends plus refunds can not exceed the bill')
                WHERE NEW.amountPaise
                    + COALESCE((SELECT SUM(o.amountPaise) FROM shared_share o WHERE o.txnId = NEW.txnId AND o.id IS NOT NEW.id), 0)
                    + COALESCE((SELECT SUM(r.amountPaise) FROM txn r WHERE r.refundOfId = NEW.txnId AND r.status != 'IGNORED'), 0)
                  > -(SELECT t.amountPaise FROM txn t WHERE t.id = NEW.txnId);
            SELECT RAISE(ABORT, 'share: can not go below what was already paid back')
                WHERE NEW.amountPaise < COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = NEW.id), 0);
        END
    """.trimIndent()

    private const val SETTLEMENT_CHECK = """
        CREATE TRIGGER IF NOT EXISTS trg_settlement_check BEFORE INSERT ON settlement
        BEGIN
            SELECT RAISE(ABORT, 'settlement: amount must be positive') WHERE NEW.amountPaise <= 0;
            SELECT RAISE(ABORT, 'settlement: credit must be a confirmed repayment')
                WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = NEW.creditTxnId AND t.kind = 'REPAYMENT' AND t.status = 'CONFIRMED');
            SELECT RAISE(ABORT, 'settlement: more than the share')
                WHERE NEW.amountPaise + COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = NEW.shareId), 0)
                    > (SELECT s.amountPaise FROM shared_share s WHERE s.id = NEW.shareId);
            SELECT RAISE(ABORT, 'settlement: more than the repayment')
                WHERE NEW.amountPaise + COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.creditTxnId = NEW.creditTxnId), 0)
                    > (SELECT t.amountPaise FROM txn t WHERE t.id = NEW.creditTxnId);
        END
    """
    private const val SETTLEMENT_NO_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_settlement_no_update BEFORE UPDATE ON settlement
        BEGIN SELECT RAISE(ABORT, 'settlement: delete and re-create instead of updating'); END
    """

    /** A shared bill / an applied repayment can't silently change into something the links no longer fit. */
    private const val TXN_SHARED_GUARD = """
        CREATE TRIGGER IF NOT EXISTS trg_txn_shared_guard BEFORE UPDATE ON txn
        BEGIN
            SELECT RAISE(ABORT, 'txn: a shared bill must stay a confirmed expense covering its shares')
                WHERE EXISTS (SELECT 1 FROM shared_share s WHERE s.txnId = NEW.id)
                  AND (NEW.kind != 'EXPENSE' OR NEW.status != 'CONFIRMED' OR NEW.accountId != OLD.accountId
                       OR -NEW.amountPaise < COALESCE((SELECT SUM(s.amountPaise) FROM shared_share s WHERE s.txnId = NEW.id), 0)
                           + COALESCE((SELECT SUM(r.amountPaise) FROM txn r WHERE r.refundOfId = NEW.id AND r.status != 'IGNORED'), 0));
            SELECT RAISE(ABORT, 'txn: undo the repayment before changing it')
                WHERE EXISTS (SELECT 1 FROM settlement x WHERE x.creditTxnId = NEW.id)
                  AND (NEW.kind != 'REPAYMENT' OR NEW.status != 'CONFIRMED' OR NEW.amountPaise != OLD.amountPaise OR NEW.accountId != OLD.accountId);
        END
    """

    private val TRIGGER_NAMES = listOf(
        "trg_txn_check_INSERT", "trg_txn_check_UPDATE", "trg_transfer_check_INSERT", "trg_transfer_check_UPDATE",
        "trg_anchor_no_update", "trg_anchor_no_delete",
        "trg_share_check_INSERT", "trg_share_check_UPDATE", "trg_settlement_check", "trg_settlement_no_update", "trg_txn_shared_guard",
    )

    val TRIGGERS: List<String> = listOf(
        txnChecks("INSERT"),
        txnChecks("UPDATE"),
        transferChecks("INSERT"),
        transferChecks("UPDATE"),
        ANCHOR_NO_UPDATE,
        ANCHOR_NO_DELETE,
        shareChecks("INSERT"),
        shareChecks("UPDATE"),
        SETTLEMENT_CHECK,
        SETTLEMENT_NO_UPDATE,
        TXN_SHARED_GUARD,
    )

    /** Drops and recreates every trigger on each open, so a changed rule always replaces the installed one. */
    fun createTriggers(db: SupportSQLiteDatabase) {
        dropTriggers(db)
        TRIGGERS.forEach(db::execSQL)
    }

    /** Only for "Delete all data" (the append-only anchor rule would block it); always followed by [createTriggers]. */
    fun dropTriggers(db: SupportSQLiteDatabase) {
        TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    }

    private data class SeedCategory(val id: Long, val name: String, val kind: String, val icon: String, val color: Int, val system: Boolean = false)

    private val SEED = listOf(
        SeedCategory(CATEGORY_TRANSFER, "Transfer", "SYSTEM", "swap_horiz", 0, system = true),
        SeedCategory(CATEGORY_REFUND, "Refund", "SYSTEM", "undo", 1, system = true),
        SeedCategory(CATEGORY_ADJUSTMENT, "Adjustment", "SYSTEM", "tune", 2, system = true),
        SeedCategory(CATEGORY_REPAYMENT, "Paid back", "SYSTEM", "handshake", 3, system = true),
        SeedCategory(CATEGORY_ALL_SPENDING, "All spending", "SYSTEM", "account_balance_wallet", 4, system = true),
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
