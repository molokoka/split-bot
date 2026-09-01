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

// Every message is sent with parse_mode HTML (see HttpTelegramApi), so any user-controlled
// text — display names, expense descriptions — must be escaped before being interpolated
// into a formatted string, or a stray '<', '>', or '&' produces malformed HTML that Telegram
// either mangles or rejects outright. Static text we write ourselves doesn't need this, but
// must in turn avoid raw '<'/'>' of its own (see HELP_TEXT and the Usage messages, which use
// <code>...</code> placeholders instead of bare <placeholder> for exactly this reason).
internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val payerName = escapeHtml(nameOf.getValue(expense.payerId).displayName)
    val base = "$payerName paid ${formatAmount(expense.amount, expense.currency)} for <b>${escapeHtml(expense.description)}</b>"

    val otherParticipants = expense.shares
        .filter { it.memberId != expense.payerId }
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { escapeHtml(nameOf.getValue(it.memberId).displayName) }

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
    return expenses.joinToString("\n\n") { formatExpenseListLine(it, members) }
}

private fun formatExpenseListLine(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val shortId = expense.id.value.take(8)
    val date = expense.createdAt.toString().take(10)
    val payerName = escapeHtml(nameOf.getValue(expense.payerId).displayName)
    val breakdown = expense.shares
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { share ->
            "${escapeHtml(nameOf.getValue(share.memberId).displayName)} ${formatAmount(share.shareAmount, expense.currency)}"
        }

    return "<code>$shortId</code>  $date  <b>${escapeHtml(expense.description)}</b>  " +
        "${formatAmount(expense.amount, expense.currency)}\n" +
        "paid by $payerName, split ${splitTypeLabel(expense.splitType)}: $breakdown"
}

fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId, currency: String): String {
    val nameOf = members.associateBy { it.id }
    val relevant = payments.filter { it.from == viewerId || it.to == viewerId }
    if (relevant.isEmpty()) return "You're all settled up!"

    return relevant.joinToString("\n") { payment ->
        val amount = formatAmount(payment.amount, currency)
        if (payment.from == viewerId) {
            "You owe ${escapeHtml(nameOf.getValue(payment.to).displayName)} $amount"
        } else {
            "${escapeHtml(nameOf.getValue(payment.from).displayName)} owes you $amount"
        }
    }
}

fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>, currency: String): String {
    if (payments.isEmpty()) return "Everyone's settled up — nothing to do!"
    val nameOf = members.associateBy { it.id }
    return payments.joinToString("\n") { payment ->
        val from = escapeHtml(nameOf.getValue(payment.from).displayName)
        val to = escapeHtml(nameOf.getValue(payment.to).displayName)
        "$from pays $to ${formatAmount(payment.amount, currency)}"
    }
}
