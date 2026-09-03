package split.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    val username: String? = null,
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val text: String? = null,
    // Telegram nests this one level deep in practice (a reply's own reply_to_message is
    // never populated), but the type is self-referential to mirror the real API shape.
    @SerialName("reply_to_message") val replyToMessage: TgMessage? = null,
)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

@Serializable
data class InlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val disabled: Boolean? = null,
)

// Setting force_reply pops the reply composer, targeted at this message, when any button
// on this keyboard is tapped (Bot API 10.3) — it applies to the whole keyboard, not a
// single button, which is why the exact-split flow uses two separate messages/keyboards
// (see the design spec's "UX overview").
@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
    @SerialName("force_reply") val forceReply: Boolean? = null,
)

@Serializable
data class TgChatMember(
    val status: String,
    val user: TgUser,
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<TgUpdate> = emptyList(),
)

@Serializable
data class GetChatAdministratorsResponse(
    val ok: Boolean,
    val result: List<TgChatMember> = emptyList(),
)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    // No default value: kotlinx.serialization omits fields left at their default unless
    // encodeDefaults is set, and this one must always be sent.
    @SerialName("parse_mode") val parseMode: String,
)

// Telegram Bot API 10.1+ "Rich Messages" (sendRichMessage). Unlike sendMessage's parse_mode
// HTML, block text here is literal — Telegram doesn't interpret markup inside it — so callers
// don't escape their strings before putting them in a block.
@Serializable
sealed interface RichBlock

@Serializable
@SerialName("paragraph")
data class RichBlockParagraph(val text: String) : RichBlock

@Serializable
@SerialName("table")
data class RichBlockTable(
    val cells: List<List<RichBlockTableCell>>,
    val caption: String? = null,
) : RichBlock

@Serializable
data class RichBlockTableCell(
    val text: String,
    @SerialName("is_header") val isHeader: Boolean? = null,
)

@Serializable
data class InputRichMessage(
    val blocks: List<RichBlock>,
)

@Serializable
data class SendRichMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("rich_message") val richMessage: InputRichMessage,
)
