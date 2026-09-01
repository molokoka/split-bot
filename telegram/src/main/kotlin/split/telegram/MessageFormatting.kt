package split.telegram

import split.core.DebtPayment
import split.core.Expense
import split.core.Member
import split.core.MemberId
import split.core.SplitType
import java.math.BigDecimal
import java.math.RoundingMode

fun formatAmount(amount: BigDecimal, currency: String): String =
    "${amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()} $currency"

fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val payerName = nameOf.getValue(expense.payerId).displayName
    val base = "$payerName paid ${formatAmount(expense.amount, expense.currency)} for ${expense.description}"

    // The payer is dropped from this list — they're already named as the payer, so
    // repeating them in "split with" reads as if they split the expense with themselves.
    // Sorted by name rather than left in expense.shares' order: that order reflects
    // incidental database row order on read (member_id happens to sort the rows), not
    // anything meaningful, so leaving it unsorted would show participants in a different,
    // effectively random order every time the same expense is displayed.
    val otherParticipants = expense.shares
        .filter { it.memberId != expense.payerId }
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { nameOf.getValue(it.memberId).displayName }

    return if (otherParticipants.isEmpty()) {
        base
    } else {
        "$base, split ${splitTypeLabel(expense.splitType)} with $otherParticipants"
    }
}

private fun splitTypeLabel(splitType: SplitType): String = when (splitType) {
    SplitType.EQUAL -> "equally"
    SplitType.EXACT -> "by exact amounts"
    SplitType.SHARES -> "by shares"
}

fun formatExpenseList(expenses: List<Expense>, members: List<Member>): String {
    if (expenses.isEmpty()) return "No expenses yet — use /add to log one."
    return expenses.joinToString("\n") { formatExpenseListLine(it, members) }
}

// Deliberately its own format rather than reusing formatExpenseConfirmation: /list is an
// audit view, so unlike the brief /add confirmation it needs the date and, critically,
// each person's actual share amount — for an EQUAL split that's implied (everyone pays the
// same), but that's the whole point of EXACT/SHARES splits: amounts differ per person, and
// naming the split type without the breakdown wouldn't say how much anyone actually owes.
private fun formatExpenseListLine(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val shortId = expense.id.value.take(8)
    val date = expense.createdAt.toString().take(10)
    val payerName = nameOf.getValue(expense.payerId).displayName
    val breakdown = expense.shares
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { share -> "${nameOf.getValue(share.memberId).displayName} ${formatAmount(share.shareAmount, expense.currency)}" }

    return "[$shortId] $date ${expense.description} ${formatAmount(expense.amount, expense.currency)}, " +
        "paid by $payerName, split ${splitTypeLabel(expense.splitType)}: $breakdown"
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
