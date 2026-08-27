package split.core

import java.math.BigDecimal

data class DebtPayment(
    val from: MemberId,
    val to: MemberId,
    val amount: BigDecimal,
)

fun simplifyDebts(balances: Map<MemberId, BigDecimal>): List<DebtPayment> {
    val debtors = ArrayDeque(
        balances.filterValues { it < BigDecimal.ZERO }
            .entries.sortedBy { it.value }
            .map { it.key to it.value.negate() },
    )
    val creditors = ArrayDeque(
        balances.filterValues { it > BigDecimal.ZERO }
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value },
    )

    val payments = mutableListOf<DebtPayment>()

    while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
        val (debtorId, debt) = debtors.removeFirst()
        val (creditorId, credit) = creditors.removeFirst()

        val transfer = debt.min(credit)
        payments += DebtPayment(debtorId, creditorId, transfer)

        val remainingDebt = debt - transfer
        val remainingCredit = credit - transfer
        if (remainingDebt > BigDecimal.ZERO) debtors.addFirst(debtorId to remainingDebt)
        if (remainingCredit > BigDecimal.ZERO) creditors.addFirst(creditorId to remainingCredit)
    }

    return payments
}
