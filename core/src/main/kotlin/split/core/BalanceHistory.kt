package split.core

import java.math.BigDecimal
import java.time.Instant

sealed interface BalanceHistoryEvent {
    val createdAt: Instant
    val currency: String
    val balancesAfter: Map<MemberId, BigDecimal>
}

data class ExpenseHistoryEvent(
    val expense: Expense,
    override val balancesAfter: Map<MemberId, BigDecimal>,
) : BalanceHistoryEvent {
    override val createdAt: Instant get() = expense.createdAt
    override val currency: String get() = expense.currency
}

data class SettlementHistoryEvent(
    val settlement: Settlement,
    override val balancesAfter: Map<MemberId, BigDecimal>,
) : BalanceHistoryEvent {
    override val createdAt: Instant get() = settlement.createdAt
    override val currency: String get() = settlement.currency
}

private sealed interface RawEvent {
    val createdAt: Instant
}

private data class RawExpense(val expense: Expense) : RawEvent {
    override val createdAt: Instant get() = expense.createdAt
}

private data class RawSettlement(val settlement: Settlement) : RawEvent {
    override val createdAt: Instant get() = settlement.createdAt
}

fun computeBalanceHistory(expenses: List<Expense>, settlements: List<Settlement>): List<BalanceHistoryEvent> =
    distinctCurrencies(expenses, settlements)
        .flatMap { currency -> computeBalanceHistory(currency, expenses, settlements) }
        .sortedBy { it.createdAt }

fun computeBalanceHistory(
    currency: String,
    expenses: List<Expense>,
    settlements: List<Settlement>,
): List<BalanceHistoryEvent> {
    val events = expenses.filter { it.currency == currency && it.deletedAt == null }.map { RawExpense(it) } +
        settlements.filter { it.currency == currency && it.deletedAt == null }.map { RawSettlement(it) }

    val balances = mutableMapOf<MemberId, BigDecimal>()
    fun add(memberId: MemberId, delta: BigDecimal) {
        balances[memberId] = balances.getOrDefault(memberId, BigDecimal.ZERO) + delta
    }

    return events.sortedBy { it.createdAt }.map { event ->
        when (event) {
            is RawExpense -> {
                add(event.expense.payerId, event.expense.amount)
                event.expense.shares.forEach { share -> add(share.memberId, -share.shareAmount) }
                ExpenseHistoryEvent(event.expense, balances.toMap())
            }
            is RawSettlement -> {
                add(event.settlement.fromMemberId, event.settlement.amount)
                add(event.settlement.toMemberId, -event.settlement.amount)
                SettlementHistoryEvent(event.settlement, balances.toMap())
            }
        }
    }
}
