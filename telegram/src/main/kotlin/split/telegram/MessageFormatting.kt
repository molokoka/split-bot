package split.telegram

import split.core.DebtPayment
import split.core.Expense
import split.core.Member
import split.core.MemberId
import java.math.BigDecimal
import java.math.RoundingMode

fun formatAmount(amount: BigDecimal, currency: String): String =
    "${amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()} $currency"

fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val payerName = nameOf.getValue(expense.payerId).displayName
    val participantNames = expense.shares.joinToString(", ") { nameOf.getValue(it.memberId).displayName }
    return "$payerName paid ${formatAmount(expense.amount, expense.currency)} for ${expense.description}, " +
        "split with $participantNames"
}

fun formatExpenseList(expenses: List<Expense>): String {
    if (expenses.isEmpty()) return "No expenses yet — use /add to log one."
    return expenses.joinToString("\n") { expense ->
        val shortId = expense.id.value.take(8)
        "[$shortId] ${expense.description} — ${formatAmount(expense.amount, expense.currency)}"
    }
}

fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId, currency: String): String {
    val nameOf = members.associateBy { it.id }
    val relevant = payments.filter { it.from == viewerId || it.to == viewerId }
    if (relevant.isEmpty()) return "You're all settled up!"

    return relevant.joinToString("\n") { payment ->
        val amount = formatAmount(payment.amount, currency)
        if (payment.from == viewerId) {
            "You owe ${nameOf.getValue(payment.to).displayName} $amount"
        } else {
            "${nameOf.getValue(payment.from).displayName} owes you $amount"
        }
    }
}

fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>, currency: String): String {
    if (payments.isEmpty()) return "Everyone's settled up — nothing to do!"
    val nameOf = members.associateBy { it.id }
    return payments.joinToString("\n") { payment ->
        val from = nameOf.getValue(payment.from).displayName
        val to = nameOf.getValue(payment.to).displayName
        "$from pays $to ${formatAmount(payment.amount, currency)}"
    }
}
