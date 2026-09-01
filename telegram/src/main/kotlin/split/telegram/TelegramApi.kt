package split.telegram

interface TelegramApi {
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>
    suspend fun sendMessage(chatId: Long, text: String)
    suspend fun sendRichMessage(chatId: Long, richMessage: InputRichMessage)
    suspend fun getChatAdministrators(chatId: Long): List<TgChatMember>
}
