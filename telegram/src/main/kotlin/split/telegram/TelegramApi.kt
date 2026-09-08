package split.telegram

interface TelegramApi {
    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
    ): List<TgUpdate>

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ): Long

    suspend fun sendForceReplyPrompt(
        chatId: Long,
        text: String,
    ): Long

    suspend fun sendRichMessage(
        chatId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup? = null,
    ): Long

    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    )

    suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup? = null,
    )

    suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    )

    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    )

    suspend fun getChatAdministrators(chatId: Long): List<TgChatMember>

    suspend fun setMyCommands(commands: List<BotCommand>)
}
