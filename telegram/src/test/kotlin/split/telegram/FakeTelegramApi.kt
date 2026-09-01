package split.telegram

class FakeTelegramApi : TelegramApi {
    val sentMessages = mutableListOf<Pair<Long, String>>()
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

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators
}
