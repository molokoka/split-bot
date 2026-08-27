package split.core

import java.math.BigDecimal

fun computeBalances(
    currency: String,
    expenses: List<Expense>,
    settlements: List<Settlement>,
): Map<MemberId, BigDecimal> {
    val balances = mutableMapOf<MemberId, BigDecimal>()

    fun add(memberId: MemberId, delta: BigDecimal) {
        balances[memberId] = balances.getOrDefault(memberId, BigDecimal.ZERO) + delta
    }

    expenses.filter { it.currency == currency }.forEach { expense ->
        add(expense.payerId, expense.amount)
        expense.shares.forEach { share -> add(share.memberId, -share.shareAmount) }
    }

    settlements.filter { it.currency == currency }.forEach { settlement ->
        add(settlement.fromMemberId, settlement.amount)
        add(settlement.toMemberId, -settlement.amount)
    }

    return balances
}
