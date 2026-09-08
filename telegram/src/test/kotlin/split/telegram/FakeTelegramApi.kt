package split.telegram

class FakeTelegramApi : TelegramApi {
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
    val setMyCommandsCalls = mutableListOf<List<BotCommand>>()
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
        return nextMessageId++
    }

    override suspend fun sendRichMessage(
        chatId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ): Long {
        sentRichMessages += chatId to richMessage
        sentKeyboards += keyboard
        return nextMessageId++
    }

    override suspend fun sendForceReplyPrompt(
        chatId: Long,
        text: String,
    ): Long {
        sentForceReplyPrompts += chatId to text
        return nextMessageId++
    }

    override suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
    ) {
        editedMessages += Triple(chatId, messageId, text)
        editedKeyboards += keyboard
    }

    override suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ) {
        editedRichMessages += Triple(chatId, messageId, richMessage)
        editedKeyboards += keyboard
    }

    override suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        deletedMessages += chatId to messageId
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ) {
        answeredCallbacks += Triple(callbackQueryId, text, showAlert)
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators

    override suspend fun setMyCommands(commands: List<BotCommand>) {
        setMyCommandsCalls += commands
    }
}
