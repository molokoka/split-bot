package split.telegram

import split.core.BalanceHistoryEvent
import split.core.DebtPayment
import split.core.Expense
import split.core.ExpenseHistoryEvent
import split.core.Member
import split.core.MemberId
import split.core.Settlement
import split.core.SettlementHistoryEvent
import split.core.SplitType
import split.core.simplifyDebts
import java.math.BigDecimal
import java.math.RoundingMode

fun formatAmount(
    amount: BigDecimal,
    currency: String,
): String = "${formatBareAmount(amount)} $currency"

internal fun formatBareAmount(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()

// Every message is sent with parse_mode HTML (see HttpTelegramApi), so any user-controlled
// text — display names, expense descriptions — must be escaped before being interpolated
// into a formatted string, or a stray '<', '>', or '&' produces malformed HTML that Telegram
// either mangles or rejects outright. Static text we write ourselves doesn't need this, but
// must in turn avoid raw '<'/'>' of its own (see HELP_TEXT and the Usage messages, which use
// <code>...</code> placeholders instead of bare <placeholder> for exactly this reason).
internal fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

// Prefers a clickable @username mention over the plain display name whenever the member has
// one on file — Telegram treats that as the normal way to name someone, and it saves the
// reader from having to match a display name to a person themselves. No escaping needed for
// the @username branch: Telegram usernames are constrained to letters, digits, and
// underscores, so they can't carry HTML metacharacters.
internal fun mentionName(
    member: Member,
    usernames: Map<MemberId, String>,
): String = usernames[member.id]?.let { "@$it" } ?: escapeHtml(member.displayName)

fun formatExpenseConfirmation(
    expense: Expense,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    val nameOf = members.associateBy { it.id }
    val payerId = expense.payerId
    val payerName = mentionName(nameOf.getValue(payerId), usernames)
    val base = "$payerName paid ${formatAmount(expense.amount, expense.currency)} for <b>${escapeHtml(expense.description)}</b>"

    val payerShare = expense.shares.find { it.memberId == payerId }
    // Sorted by name rather than left in expense.shares' order: that order reflects
    // incidental database row order on read (member_id happens to sort the rows), not
    // anything meaningful, so leaving it unsorted would show participants in a different,
    // effectively random order every time the same expense is displayed. The payer is
    // listed first so their own share reads immediately next to how much they paid,
    // instead of leaving the reader to subtract everyone else's share from the total.
    val otherShares =
        expense.shares
            .filter { it.memberId != payerId }
            .sortedBy { nameOf.getValue(it.memberId).displayName }

    if (otherShares.isEmpty()) return "Expense added:\n\n$base"

    val allShares = listOfNotNull(payerShare) + otherShares
    val participants =
        allShares.joinToString(", ") { share ->
            "${mentionName(nameOf.getValue(share.memberId), usernames)} (${formatBareAmount(share.shareAmount)})"
        }

    return "Expense added:\n\n$base, split ${splitTypeLabel(expense.splitType)}: $participants"
}

private fun splitTypeLabel(splitType: SplitType): String =
    when (splitType) {
        SplitType.EQUAL -> "equally"
        SplitType.EXACT -> "by exact amounts"
        SplitType.SHARES -> "by shares"
    }

fun buildExpenseListMessage(
    expenses: List<Expense>,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): InputRichMessage {
    if (expenses.isEmpty()) {
        return InputRichMessage(blocks = listOf(RichBlockParagraph("No expenses yet — use /split to log one.")))
    }

    val header =
        listOf("Expense", "Split").map {
            RichBlockTableCell(text = it, isHeader = true)
        }
    val nameOf = members.associateBy { it.id }
    val rows = expenses.map { expenseRow(it, nameOf, usernames) }
    return InputRichMessage(
        blocks = listOf(RichBlockTable(cells = listOf(header) + rows, caption = "Last 10 expenses:")),
    )
}

// Deliberately its own format rather than reusing formatExpenseConfirmation: /expenses is an
// audit view, so unlike the brief /split confirmation it needs the date and, critically,
// each person's actual share amount — for an EQUAL split that's implied (everyone pays the
// same), but that's the whole point of EXACT/SHARES splits: amounts differ per person, and
// naming the split type without the breakdown wouldn't say how much anyone actually owes.
private fun expenseRow(
    expense: Expense,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
): List<RichBlockTableCell> {
    val shortId = expense.id.value.take(8)
    val date = expense.createdAt.toString().take(10)
    val payerName = plainName(nameOf.getValue(expense.payerId), usernames)
    val breakdown =
        expense.shares
            .sortedBy { nameOf.getValue(it.memberId).displayName }
            .joinToString("\n") { share ->
                "${plainName(nameOf.getValue(share.memberId), usernames)} ${formatAmount(share.shareAmount, expense.currency)}"
            }

    val summary =
        "${expense.description}\n${formatAmount(expense.amount, expense.currency)} · paid by $payerName\n" +
            "$shortId · $date"

    return listOf(summary, "${splitTypeLabel(expense.splitType)}:\n$breakdown").map { RichBlockTableCell(text = it) }
}

// Same @username-or-display-name preference as mentionName, but without HTML escaping: rich
// message block text is literal, unlike the parse_mode HTML used by sendMessage, so escaping
// here would show a literal "&amp;" instead of "&" for a name like "Bob & Sons".
internal fun plainName(
    member: Member,
    usernames: Map<MemberId, String>,
): String = usernames[member.id]?.let { "@$it" } ?: member.displayName

fun buildSettlementListMessage(
    settlements: List<Settlement>,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): InputRichMessage {
    if (settlements.isEmpty()) {
        return InputRichMessage(blocks = listOf(RichBlockParagraph("No settlements recorded yet — use /settle to log one.")))
    }

    val header =
        listOf("Date", "From", "To", "Amount").map {
            RichBlockTableCell(text = it, isHeader = true)
        }
    val nameOf = members.associateBy { it.id }
    val rows = settlements.map { settlementRow(it, nameOf, usernames) }
    return InputRichMessage(
        blocks = listOf(RichBlockTable(cells = listOf(header) + rows, caption = "Last 10 settlements:")),
    )
}

private fun settlementRow(
    settlement: Settlement,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
): List<RichBlockTableCell> {
    val date = settlement.createdAt.toString().take(10)
    val from = plainName(nameOf.getValue(settlement.fromMemberId), usernames)
    val to = plainName(nameOf.getValue(settlement.toMemberId), usernames)

    return listOf(date, from, to, formatAmount(settlement.amount, settlement.currency)).map { RichBlockTableCell(text = it) }
}

fun formatBalances(
    paymentsByCurrency: Map<String, List<DebtPayment>>,
    members: List<Member>,
    viewerId: MemberId,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    val nameOf = members.associateBy { it.id }
    val lines =
        paymentsByCurrency.toSortedMap().flatMap { (currency, payments) ->
            payments.filter { it.from == viewerId || it.to == viewerId }.map { payment ->
                val amount = formatAmount(payment.amount, currency)
                if (payment.from == viewerId) {
                    "You owe ${mentionName(nameOf.getValue(payment.to), usernames)} $amount"
                } else {
                    "${mentionName(nameOf.getValue(payment.from), usernames)} owes you $amount"
                }
            }
        }
    if (lines.isEmpty()) return "You're all settled up!"

    return "Balances:\n\n" + lines.joinToString("\n")
}

fun formatSettleSuggestions(
    paymentsByCurrency: Map<String, List<DebtPayment>>,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): String {
    val nameOf = members.associateBy { it.id }
    val lines =
        paymentsByCurrency.toSortedMap().flatMap { (currency, payments) ->
            payments.map { payment ->
                val from = mentionName(nameOf.getValue(payment.from), usernames)
                val to = mentionName(nameOf.getValue(payment.to), usernames)
                "$from to pay $to ${formatAmount(payment.amount, currency)}"
            }
        }
    if (lines.isEmpty()) return "Everyone's settled up — nothing to do!"

    return "Suggested settlements:\n\n" + lines.joinToString("\n")
}

fun buildHistoryMessage(
    events: List<BalanceHistoryEvent>,
    members: List<Member>,
    usernames: Map<MemberId, String> = emptyMap(),
): InputRichMessage {
    if (events.isEmpty()) {
        return InputRichMessage(blocks = listOf(RichBlockParagraph("No history yet — use /split or /settle to get started.")))
    }

    val header =
        listOf("Event", "Balances").map {
            RichBlockTableCell(text = it, isHeader = true)
        }
    val nameOf = members.associateBy { it.id }
    val rows = events.map { historyRow(it, nameOf, usernames) }
    return InputRichMessage(
        blocks = listOf(RichBlockTable(cells = listOf(header) + rows, caption = "Last 20 events:")),
    )
}

private fun historyRow(
    event: BalanceHistoryEvent,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
): List<RichBlockTableCell> {
    val eventCell =
        when (event) {
            is ExpenseHistoryEvent -> {
                val payer = plainName(nameOf.getValue(event.expense.payerId), usernames)
                "${event.expense.createdAt.toString().take(10)} · ${event.expense.description} — " +
                    "$payer paid ${formatAmount(event.expense.amount, event.currency)}"
            }
            is SettlementHistoryEvent -> {
                val from = plainName(nameOf.getValue(event.settlement.fromMemberId), usernames)
                val to = plainName(nameOf.getValue(event.settlement.toMemberId), usernames)
                "${event.settlement.createdAt.toString().take(
                    10,
                )} · $from paid $to ${formatAmount(event.settlement.amount, event.currency)}"
            }
        }

    val payments = simplifyDebts(event.balancesAfter)
    val owesCell =
        if (payments.isEmpty()) {
            "Everyone's settled up in ${event.currency}"
        } else {
            payments.joinToString("\n") { payment ->
                val from = plainName(nameOf.getValue(payment.from), usernames)
                val to = plainName(nameOf.getValue(payment.to), usernames)
                "$from owes $to ${formatAmount(payment.amount, event.currency)}"
            }
        }

    return listOf(eventCell, owesCell).map { RichBlockTableCell(text = it) }
}
