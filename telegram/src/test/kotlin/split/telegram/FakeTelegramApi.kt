package split.telegram

class FakeTelegramApi : TelegramApi {
    val sentMessages = mutableListOf<Pair<Long, String>>()
    val sentRichMessages = mutableListOf<Pair<Long, InputRichMessage>>()
    var chatAdministrators: List<TgChatMember> = emptyList()
    var updatesToReturn: List<TgUpdate> = emptyList()
    var getUpdatesException: Exception? = null

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        getUpdatesException?.let { throw it }
        return updatesToReturn
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        sentMessages += chatId to text
    }

    override suspend fun sendRichMessage(chatId: Long, richMessage: InputRichMessage) {
        sentRichMessages += chatId to richMessage
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators
}
