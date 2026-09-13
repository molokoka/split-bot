package split.telegram.splitflow

import split.core.Member
import split.core.MemberId
import split.telegram.api.InlineKeyboardButton
import split.telegram.api.InlineKeyboardMarkup
import split.telegram.api.InputRichMessage
import split.telegram.api.RichBlockParagraph
import split.telegram.api.RichBlockTable
import split.telegram.api.RichBlockTableCell
import split.telegram.formatAmount
import split.telegram.plainName
import java.math.BigDecimal

const val SPLIT_MODE_PROMPT = "How should this be split?"
const val SPLIT_MODE_EQUAL_DATA = "split:mode:equal"
const val SPLIT_MODE_EXACT_DATA = "split:mode:exact"
const val SPLIT_CANCEL_DATA = "split:cancel"
const val SPLIT_CONFIRM_DATA = "split:confirm"
const val SPLIT_PICK_PREFIX = "split:pick:"

fun splitPickData(index: Int): String = "$SPLIT_PICK_PREFIX$index"

fun splitModeKeyboard(): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
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
    val rows =
        participantIds.map { memberId ->
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
    val buttons =
        participantIds.mapIndexed { index, memberId ->
            val entered = amountsEntered[memberId]
            val name = plainName(nameOf.getValue(memberId), usernames)
            val label = if (entered != null) "$name ✓ ${formatAmount(entered, currency)}" else name
            InlineKeyboardButton(text = label, callbackData = splitPickData(index))
        }
    return InlineKeyboardMarkup(inlineKeyboard = buttons.map { listOf(it) })
}

fun splitAmountPromptText(name: String): String = "How much is $name's share? Reply to this message with an amount."

fun splitDraftPromptText(
    awaiting: SplitDraftField,
    currency: String,
    knownUsernames: List<String> = emptyList(),
): String =
    when (awaiting) {
        SplitDraftField.DESCRIPTION -> "What's this expense for? Reply with a short description, e.g. \"dinner\"."
        SplitDraftField.AMOUNT -> "How much, and in what currency? Reply with an amount, e.g. 90 or 90 EUR (defaults to $currency)."
        SplitDraftField.PARTICIPANTS ->
            if (knownUsernames.isEmpty()) {
                "Who split this with you? Reply with their usernames, e.g. <code>@alice @bob</code> — " +
                    "they'll need to send me /start first so I recognize them."
            } else {
                "Who split this with you? Reply with their usernames. Members:\n\n" +
                    knownUsernames.joinToString("\n") { "• @$it" }
            }
    }

fun splitActionsText(
    amountsEntered: Map<MemberId, BigDecimal>,
    amount: BigDecimal,
    currency: String,
): String {
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return "Entered ${formatAmount(entered, currency)} of ${formatAmount(amount, currency)}"
}

fun splitActionsKeyboard(canConfirm: Boolean): InlineKeyboardMarkup {
    val cancelButton = InlineKeyboardButton(text = "Cancel", callbackData = SPLIT_CANCEL_DATA)
    val row =
        if (canConfirm) {
            listOf(cancelButton, InlineKeyboardButton(text = "Confirm", callbackData = SPLIT_CONFIRM_DATA))
        } else {
            listOf(cancelButton)
        }
    return InlineKeyboardMarkup(inlineKeyboard = listOf(row))
}

fun splitIsReadyToConfirm(
    participantIds: List<MemberId>,
    amountsEntered: Map<MemberId, BigDecimal>,
    amount: BigDecimal,
): Boolean {
    if (!participantIds.all { it in amountsEntered }) return false
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return entered.compareTo(amount) == 0
}

const val PENDING_ENTER_PREFIX = "pending:enter:"

fun pendingSplitEnterData(promptMessageId: Long): String = "$PENDING_ENTER_PREFIX$promptMessageId"

fun buildPendingSplitsMessage(
    flows: List<PendingSplit>,
    members: List<Member>,
    usernames: Map<MemberId, String>,
): InputRichMessage {
    if (flows.isEmpty()) {
        return InputRichMessage(blocks = listOf(RichBlockParagraph("No pending splits.")))
    }
    val nameOf = members.associateBy { it.id }
    val header = listOf("Split", "Status").map { RichBlockTableCell(text = it, isHeader = true) }
    val rows = flows.map { flow -> pendingSplitRow(flow, nameOf, usernames) }
    return InputRichMessage(blocks = listOf(RichBlockTable(cells = listOf(header) + rows)))
}

private fun pendingSplitRow(
    flow: PendingSplit,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
): List<RichBlockTableCell> {
    val summary = "${flow.description}\n${formatAmount(flow.amount, flow.currency)}"
    val status =
        flow.participantIds.joinToString("\n") { memberId ->
            val name = plainName(nameOf.getValue(memberId), usernames)
            val amount = flow.amountsEntered[memberId]?.let { formatAmount(it, flow.currency) } ?: "—"
            "$name $amount"
        }
    return listOf(summary, status).map { RichBlockTableCell(text = it) }
}

fun pendingSplitsKeyboard(flows: List<PendingSplit>): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard =
            flows.map { flow ->
                listOf(
                    InlineKeyboardButton(
                        text = "Enter your amount — ${flow.description}",
                        callbackData = pendingSplitEnterData(flow.promptMessageId),
                    ),
                )
            },
    )
