package split.telegram

import split.telegram.api.InlineKeyboardMarkup
import split.telegram.api.InputRichMessage
import split.telegram.api.TelegramApi
import split.telegram.api.TgChatMember
import split.telegram.api.TgUpdate

class FakeTelegramApi : TelegramApi {
    /** Every send/edit/delete in order, so a spec can render the chat rather than sift parallel lists. */
    val events = mutableListOf<ChatEvent>()

    /** Who is acting right now — set by the fixture so an alert can name who saw it. */
    var currentActor: String? = null
    val sentMessages = mutableListOf<Pair<Long, String>>()
    val sentRichMessages = mutableListOf<Pair<Long, InputRichMessage>>()
    val sentKeyboards = mutableListOf<InlineKeyboardMarkup?>()
    val sentForceReplyPrompts = mutableListOf<Pair<Long, String>>()
    val editedMessages = mutableListOf<Triple<Long, Long, String>>()
    val editedRichMessages = mutableListOf<Triple<Long, Long, InputRichMessage>>()
    val editedKeyboards = mutableListOf<InlineKeyboardMarkup?>()
    val deletedMessages = mutableListOf<Pair<Long, Long>>()
    val answeredCallbacks = mutableListOf<Triple<String, String?, Boolean>>()
    var chatAdministrators: List<TgChatMember> = emptyList()
    var updatesToReturn: List<TgUpdate> = emptyList()
    var getUpdatesException: Exception? = null
    private var nextMessageId = 1L

    override suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
    ): List<TgUpdate> {
        getUpdatesException?.let { throw it }
        return updatesToReturn
    }

    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
    ): Long {
        sentMessages += chatId to text
        sentKeyboards += keyboard
        val messageId = nextMessageId++
        events += BotSent(messageId = messageId, text = text, keyboard = keyboard)
        return messageId
    }

    override suspend fun sendRichMessage(
        chatId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ): Long {
        sentRichMessages += chatId to richMessage
        sentKeyboards += keyboard
        val messageId = nextMessageId++
        events += BotSent(messageId = messageId, richMessage = richMessage, keyboard = keyboard)
        return messageId
    }

    override suspend fun sendForceReplyPrompt(
        chatId: Long,
        text: String,
    ): Long {
        sentForceReplyPrompts += chatId to text
        val messageId = nextMessageId++
        events += BotSent(messageId = messageId, text = text, isForceReply = true)
        return messageId
    }

    override suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
    ) {
        editedMessages += Triple(chatId, messageId, text)
        editedKeyboards += keyboard
        events += BotEdited(messageId = messageId, text = text, keyboard = keyboard)
    }

    override suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ) {
        editedRichMessages += Triple(chatId, messageId, richMessage)
        editedKeyboards += keyboard
        events += BotEdited(messageId = messageId, richMessage = richMessage, keyboard = keyboard)
    }

    override suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        deletedMessages += chatId to messageId
        events += BotDeleted(messageId = messageId)
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ) {
        answeredCallbacks += Triple(callbackQueryId, text, showAlert)
        text?.let { events += BotAlerted(actor = currentActor, text = it) }
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators

    /** Records that [actor] sent [text] into the chat, optionally as a reply to [replyToMessageId]. */
    fun userSaid(
        actor: String,
        text: String,
        replyToMessageId: Long? = null,
    ) {
        currentActor = actor
        events += UserSent(actor = actor, text = text, replyToMessageId = replyToMessageId)
    }

    /**
     * Records that [actor] tapped the button carrying [callbackData] on [messageId], labelling it
     * from that message's live keyboard so the transcript shows what they saw. Falls back to the raw
     * callback data when the button isn't (or is no longer) there.
     */
    fun userTapped(
        actor: String,
        messageId: Long,
        callbackData: String,
    ) {
        currentActor = actor
        val label = buttonLabelOn(events, messageId, callbackData) ?: callbackData
        events += UserTapped(actor = actor, buttonLabel = label, messageId = messageId)
    }
}
