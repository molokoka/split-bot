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

// Prefers a clickable @username mention over the plain display name whenever the member has
// one on file — Telegram treats that as the normal way to name someone, and it saves the
// reader from having to match a display name to a person themselves. No escaping needed for
// the @username branch: Telegram usernames are constrained to letters, digits, and
// underscores, so they can't carry HTML metacharacters.
internal fun mentionName(member: Member, usernames: Map<MemberId, String>): String =
    usernames[member.id]?.let { "@$it" } ?: escapeHtml(member.displayName)

fun formatExpenseConfirmation(
    expense: Expense,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    val nameOf = members.associateBy { it.id }
    val payerName = mentionName(nameOf.getValue(expense.payerId), usernames)
    val base = "$payerName paid ${formatAmount(expense.amount, expense.currency)} for <b>${escapeHtml(expense.description)}</b>"

    // The payer is dropped from this list — they're already named as the payer, so
    // repeating them in "split with" reads as if they split the expense with themselves.
    // Sorted by name rather than left in expense.shares' order: that order reflects
    // incidental database row order on read (member_id happens to sort the rows), not
    // anything meaningful, so leaving it unsorted would show participants in a different,
    // effectively random order every time the same expense is displayed.
    val otherParticipants = expense.shares
        .filter { it.memberId != expense.payerId }
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { mentionName(nameOf.getValue(it.memberId), usernames) }

    return "Expense added:\n\n" + if (otherParticipants.isEmpty()) {
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

fun formatExpenseList(expenses: List<Expense>, members: List<Member>, usernames: Map<MemberId, String> = emptyMap()): String {
    if (expenses.isEmpty()) return "No expenses yet — use /add to log one."
    // Blank line between entries, since a wall of unbroken lines is hard to scan in Telegram.
    // Header names the 10-expense cap ListCommand applies, so it's not a mystery why an
    // older expense might be missing from the list. Plain text, not bold — a bold title
    // read as more visually important than the bold expense descriptions right below it.
    return "Last 10 expenses:\n\n" +
        expenses.joinToString("\n\n") { formatExpenseListLine(it, members, usernames) }
}

// Deliberately its own format rather than reusing formatExpenseConfirmation: /list is an
// audit view, so unlike the brief /add confirmation it needs the date and, critically,
// each person's actual share amount — for an EQUAL split that's implied (everyone pays the
// same), but that's the whole point of EXACT/SHARES splits: amounts differ per person, and
// naming the split type without the breakdown wouldn't say how much anyone actually owes.
private fun formatExpenseListLine(expense: Expense, members: List<Member>, usernames: Map<MemberId, String>): String {
    val nameOf = members.associateBy { it.id }
    val shortId = expense.id.value.take(8)
    val date = expense.createdAt.toString().take(10)
    val payerName = mentionName(nameOf.getValue(expense.payerId), usernames)
    val breakdown = expense.shares
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { share ->
            "${mentionName(nameOf.getValue(share.memberId), usernames)} ${formatAmount(share.shareAmount, expense.currency)}"
        }

    return "<code>$shortId</code>  $date  <b>${escapeHtml(expense.description)}</b>  " +
        "${formatAmount(expense.amount, expense.currency)}\n" +
        "paid by $payerName, split ${splitTypeLabel(expense.splitType)}: $breakdown"
}

fun formatBalances(
    payments: List<DebtPayment>,
    members: List<Member>,
    viewerId: MemberId,
    currency: String,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    val nameOf = members.associateBy { it.id }
    val relevant = payments.filter { it.from == viewerId || it.to == viewerId }
    if (relevant.isEmpty()) return "You're all settled up!"

    return "Balances:\n\n" + relevant.joinToString("\n") { payment ->
        val amount = formatAmount(payment.amount, currency)
        if (payment.from == viewerId) {
            "You owe ${mentionName(nameOf.getValue(payment.to), usernames)} $amount"
        } else {
            "${mentionName(nameOf.getValue(payment.from), usernames)} owes you $amount"
        }
    }
}

fun formatSettleSuggestions(
    payments: List<DebtPayment>,
    members: List<Member>,
    currency: String,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    if (payments.isEmpty()) return "Everyone's settled up — nothing to do!"
    val nameOf = members.associateBy { it.id }
    return "Suggested settlements:\n\n" + payments.joinToString("\n") { payment ->
        val from = mentionName(nameOf.getValue(payment.from), usernames)
        val to = mentionName(nameOf.getValue(payment.to), usernames)
        "$from pays $to ${formatAmount(payment.amount, currency)}"
    }
}
