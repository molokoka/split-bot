package split.telegram

import split.core.Member
import split.core.MemberId
import java.math.BigDecimal

const val SPLIT_MODE_PROMPT = "How should this be split?"
const val SPLIT_MODE_EQUAL_DATA = "split:mode:equal"
const val SPLIT_MODE_EXACT_DATA = "split:mode:exact"
const val SPLIT_CANCEL_DATA = "split:cancel"
const val SPLIT_CONFIRM_DATA = "split:confirm"
const val SPLIT_PICK_PREFIX = "split:pick:"

fun splitPickData(index: Int): String = "$SPLIT_PICK_PREFIX$index"

fun splitModeKeyboard(): InlineKeyboardMarkup = InlineKeyboardMarkup(
    inlineKeyboard = listOf(
        listOf(
            InlineKeyboardButton(text = "Equal", callbackData = SPLIT_MODE_EQUAL_DATA),
            InlineKeyboardButton(text = "Exact", callbackData = SPLIT_MODE_EXACT_DATA),
        ),
    ),
)

fun splitAmountsTable(
    participantIds: List<MemberId>,
    amountsEntered: Map<MemberId, BigDecimal>,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
    currency: String,
): InputRichMessage {
    val header = listOf("Person", "Amount").map { RichBlockTableCell(text = it, isHeader = true) }
    val rows = participantIds.map { memberId ->
        val amountText = amountsEntered[memberId]?.let { formatAmount(it, currency) } ?: "—"
        listOf(RichBlockTableCell(plainName(nameOf.getValue(memberId), usernames)), RichBlockTableCell(amountText))
    }
    return InputRichMessage(blocks = listOf(RichBlockTable(cells = listOf(header) + rows)))
}

fun splitParticipantKeyboard(
    participantIds: List<MemberId>,
    amountsEntered: Map<MemberId, BigDecimal>,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
    currency: String,
): InlineKeyboardMarkup {
    val buttons = participantIds.mapIndexed { index, memberId ->
        val entered = amountsEntered[memberId]
        val name = plainName(nameOf.getValue(memberId), usernames)
        val label = if (entered != null) "$name ✓ ${formatAmount(entered, currency)}" else name
        InlineKeyboardButton(text = label, callbackData = splitPickData(index))
    }
    return InlineKeyboardMarkup(inlineKeyboard = buttons.map { listOf(it) })
}

fun splitAmountPromptText(name: String): String = "How much is $name's share? Reply to this message with an amount."

fun splitDraftPromptText(awaiting: SplitDraftField, currency: String, knownUsernames: List<String> = emptyList()): String = when (awaiting) {
    SplitDraftField.DESCRIPTION -> "What's this expense for? Reply with a short description, e.g. \"dinner\"."
    SplitDraftField.AMOUNT -> "How much, and in what currency? Reply with an amount, e.g. 90 or 90 EUR (defaults to $currency)."
    SplitDraftField.PARTICIPANTS -> if (knownUsernames.isEmpty()) {
        "Who split this with you? Reply with their usernames, e.g. <code>@alice @bob</code>."
    } else {
        "Who split this with you? Reply with their usernames. Members:\n\n" +
            knownUsernames.joinToString("\n") { "• @$it" }
    }
}

fun splitActionsText(amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal, currency: String): String {
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return "Entered ${formatAmount(entered, currency)} of ${formatAmount(amount, currency)}"
}

fun splitActionsKeyboard(canConfirm: Boolean): InlineKeyboardMarkup = InlineKeyboardMarkup(
    inlineKeyboard = listOf(
        listOf(
            InlineKeyboardButton(text = "Cancel", callbackData = SPLIT_CANCEL_DATA),
            InlineKeyboardButton(text = "Confirm", callbackData = SPLIT_CONFIRM_DATA, disabled = !canConfirm),
        ),
    ),
)

fun splitIsReadyToConfirm(participantIds: List<MemberId>, amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal): Boolean {
    if (!participantIds.all { it in amountsEntered }) return false
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return entered.compareTo(amount) == 0
}
