package split.telegram

/**
 * A readable rendering of what a group chat looks like after a flow has run, so a spec can assert on
 * the conversation participants actually see rather than on the fields of the stored flow.
 *
 * The rendering is the *current* chat window, not a call log: edits show their latest text in the
 * position where the message was first sent, and deleted messages are gone — exactly what someone
 * scrolling up would find.
 */
sealed interface ChatEvent

data class BotSent(
    val messageId: Long,
    val text: String? = null,
    val richMessage: InputRichMessage? = null,
    val keyboard: InlineKeyboardMarkup? = null,
    val isForceReply: Boolean = false,
) : ChatEvent

data class BotEdited(
    val messageId: Long,
    val text: String? = null,
    val richMessage: InputRichMessage? = null,
    val keyboard: InlineKeyboardMarkup? = null,
) : ChatEvent

data class BotDeleted(
    val messageId: Long,
) : ChatEvent

data class UserSent(
    val actor: String,
    val text: String,
    val replyToMessageId: Long? = null,
) : ChatEvent

data class UserTapped(
    val actor: String,
    val buttonLabel: String,
    val messageId: Long,
) : ChatEvent

data class BotAlerted(
    val actor: String?,
    val text: String,
) : ChatEvent

private const val CONTINUATION_INDENT = "     "

/**
 * Renders [events] as a chat window. [tail], when given, keeps only the last n entries — one entry
 * being one message or one thing a person did, however many lines it renders as.
 */
fun renderChat(
    events: List<ChatEvent>,
    tail: Int? = null,
): String {
    val latest = foldMessageStates(events)
    val entries =
        events.mapNotNull { event ->
            when (event) {
                is BotSent -> latest[event.messageId]?.let { renderBotMessage(event.messageId, it) }
                is UserSent -> renderUserSent(event)
                is UserTapped -> "[${event.actor}] taps [${event.buttonLabel}] on #${event.messageId}"
                is BotAlerted -> renderAlert(event)
                is BotEdited, is BotDeleted -> null // folded into the message's final state
            }
        }
    return (tail?.let { entries.takeLast(it) } ?: entries).joinToString("\n")
}

private class MessageState(
    var text: String?,
    var richMessage: InputRichMessage?,
    var keyboard: InlineKeyboardMarkup?,
    val isForceReply: Boolean,
)

/** The final state of every message still visible, keyed by message id. Deleted ones are absent. */
private fun foldMessageStates(events: List<ChatEvent>): Map<Long, MessageState> {
    val states = mutableMapOf<Long, MessageState>()
    events.forEach { event ->
        when (event) {
            is BotSent ->
                states[event.messageId] =
                    MessageState(event.text, event.richMessage, event.keyboard, event.isForceReply)
            is BotEdited -> states[event.messageId]?.apply(event)
            is BotDeleted -> states.remove(event.messageId)
            is UserSent, is UserTapped, is BotAlerted -> {}
        }
    }
    return states
}

/** Applies an edit: a message becomes whichever of text or rich content the edit carried. */
private fun MessageState.apply(edit: BotEdited) {
    if (edit.text != null) {
        text = edit.text
        richMessage = null
    }
    if (edit.richMessage != null) {
        richMessage = edit.richMessage
        text = null
    }
    keyboard = edit.keyboard
}

private fun renderUserSent(event: UserSent): String {
    val prefix = event.replyToMessageId?.let { "↳#$it " } ?: ""
    return "[${event.actor}] $prefix${event.text}"
}

private fun renderAlert(event: BotAlerted): String =
    if (event.actor == null) {
        "(alert: \"${event.text}\")"
    } else {
        "(alert to ${event.actor}: \"${event.text}\")"
    }

private fun renderBotMessage(
    messageId: Long,
    state: MessageState,
): String {
    val header = if (state.isForceReply) "#$messageId bot (force reply):" else "#$messageId bot:"
    val bodyLines =
        state.richMessage?.let { renderRichMessage(it) }
            ?: state.text?.lines()
            ?: emptyList()
    val lines =
        when {
            state.richMessage != null -> listOf(header) + bodyLines.map(::indented)
            bodyLines.isEmpty() -> listOf(header)
            else -> listOf("$header ${bodyLines.first()}") + bodyLines.drop(1).map(::indented)
        }
    val keyboardLines =
        state.keyboard?.inlineKeyboard?.map { row ->
            CONTINUATION_INDENT + row.joinToString(" ") { "[${it.text}]" }
        } ?: emptyList()
    return (lines + keyboardLines).joinToString("\n")
}

/** Indents a continuation line, leaving a blank line blank rather than padding it with spaces. */
private fun indented(line: String): String = if (line.isBlank()) "" else CONTINUATION_INDENT + line

private fun renderRichMessage(richMessage: InputRichMessage): List<String> =
    richMessage.blocks.flatMap { block ->
        when (block) {
            is RichBlockParagraph -> block.text.lines()
            is RichBlockTable -> renderTable(block)
        }
    }

/**
 * Renders a table with every column wide enough for its widest cell line. A cell containing
 * newlines — the pending list packs "description\namount" into one cell — spreads over continuation
 * lines inside its own row, which is how Telegram lays it out.
 */
private fun renderTable(table: RichBlockTable): List<String> {
    val rows = table.cells.map { row -> row.map { it.text.lines() } }
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    val widths =
        (0 until columnCount).map { column ->
            rows.maxOf { row -> row.getOrNull(column)?.maxOf { it.length } ?: 0 }
        }
    return rows.flatMap { row ->
        val height = row.maxOfOrNull { it.size } ?: 0
        (0 until height).map { line ->
            (0 until columnCount)
                .joinToString(" | ", prefix = "| ", postfix = " |") { column ->
                    (row.getOrNull(column)?.getOrNull(line) ?: "").padEnd(widths[column])
                }
        }
    }
}

/**
 * The label of the button carrying [callbackData] on message [messageId], if that button is still
 * on that message. Absent when a spec deliberately taps a stale or non-existent button.
 */
fun buttonLabelOn(
    events: List<ChatEvent>,
    messageId: Long,
    callbackData: String,
): String? =
    foldMessageStates(events)[messageId]
        ?.keyboard
        ?.inlineKeyboard
        ?.flatten()
        ?.firstOrNull { it.callbackData == callbackData }
        ?.text

/**
 * Every visible message carrying a button labelled [label], as message id to callback data, oldest
 * first. More than one means two open flows are showing the same label.
 */
fun visibleButtons(
    events: List<ChatEvent>,
    label: String,
): List<Pair<Long, String>> =
    foldMessageStates(events)
        .toSortedMap()
        .flatMap { (messageId, state) ->
            state.keyboard
                ?.inlineKeyboard
                ?.flatten()
                ?.filter { it.text == label }
                ?.mapNotNull { button -> button.callbackData?.let { messageId to it } }
                ?: emptyList()
        }

/** The newest force-reply prompt still in the chat — the one a person would naturally reply to. */
fun newestForceReplyPrompt(events: List<ChatEvent>): Long? =
    foldMessageStates(events)
        .filterValues { it.isForceReply }
        .keys
        .maxOrNull()
