package com.spendwise.app.data.db.entity

/** Stored by name (Room's built-in enum support). Renaming a constant requires a migration. */

enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, CASH, OTHER }

/**
 * The sign of `amountPaise` is fixed per kind and enforced by a SQL trigger (ADR-004):
 * money out of this account is negative, money in is positive.
 */
enum class TxnKind(val direction: Direction, val isTransferFamily: Boolean = false) {
    EXPENSE(Direction.OUT),
    INCOME(Direction.IN),
    TRANSFER_OUT(Direction.OUT, isTransferFamily = true),
    TRANSFER_IN(Direction.IN, isTransferFamily = true),
    /** Bank leg of bank → Cash. Never an expense. */
    ATM_WITHDRAWAL(Direction.OUT, isTransferFamily = true),
    /** Bank leg of Cash → bank. Never income. */
    CASH_DEPOSIT(Direction.IN, isTransferFamily = true),
    REFUND(Direction.IN),
    FEE(Direction.OUT),
    INTEREST(Direction.IN),
    REVERSAL(Direction.EITHER),
    UNKNOWN(Direction.EITHER);

    enum class Direction { IN, OUT, EITHER }
}

enum class TxnStatus { PENDING, CONFIRMED, IGNORED }

enum class Confidence { HIGH, NEEDS_REVIEW, LOW }

enum class TimeSource { BODY, RECEIVED, MANUAL }

enum class CounterpartyKind { PERSON, MERCHANT, UNKNOWN }

enum class PaymentMethod { UPI, CARD, NETBANKING, NEFT, IMPS, RTGS, ATM, CASH, CHEQUE, AUTO_DEBIT, OTHER, UNKNOWN }

enum class MessageSource { SMS_LIVE, SMS_INBOX, MANUAL_IMPORT }

/** UNKNOWN_ACCOUNT: a readable transaction for a bank account not in the app and not marked "Not mine" (ADR-005 §7). */
enum class ParseStatus { RECEIVED, PARSED, NOT_FINANCIAL, FINANCIAL_UNPARSED, UNKNOWN_ACCOUNT, NOT_MINE, ERROR }

enum class AnchorSource { OPENING, MANUAL, RECONCILE }

enum class CategoryKind { EXPENSE, INCOME, SYSTEM }

enum class RecurringKind { SUBSCRIPTION, BILL, EMI, RENT, INSURANCE, OTHER }

enum class Cadence { WEEKLY, MONTHLY, QUARTERLY, YEARLY }
