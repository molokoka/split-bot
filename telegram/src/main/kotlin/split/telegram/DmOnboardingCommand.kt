package split.telegram

internal const val ADD_TO_GROUP_DEEP_LINK_PAYLOAD = "split"

private fun addToGroupKeyboard(botUsername: String): InlineKeyboardMarkup {
    val addToGroupUrl = "https://t.me/$botUsername?startgroup=$ADD_TO_GROUP_DEEP_LINK_PAYLOAD"
    return InlineKeyboardMarkup(
        inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "➕ Add me to a group", url = addToGroupUrl))),
    )
}

class DmStartCommand(
    private val botUsername: String,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: DmCommandContext) {
        telegramApi.sendMessage(
            context.chatId,
            "👋 Hi! I split expenses for groups of friends, right inside Telegram.\n\n" +
                "I only work inside a group chat — I can't track anything here in a DM.\n\n" +
                "To get started:\n" +
                "1. Tap the button below and pick a group\n" +
                "2. Once I'm in, everyone who wants to be @mentioned in /split should also send me /start — " +
                "same as you just did here\n" +
                "3. Then just /split an expense and I'll take it from there",
            addToGroupKeyboard(botUsername),
        )
    }
}

class DmFallbackCommand(
    private val botUsername: String,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: DmCommandContext) {
        telegramApi.sendMessage(
            context.chatId,
            "I only work inside group chats — add me to one first.",
            addToGroupKeyboard(botUsername),
        )
    }
}
