package split.telegram

import split.core.GroupRepository

class CurrencyCommand(
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val code = context.args.trim().uppercase()
        if (!Regex("^[A-Z]{3}$").matches(code)) {
            telegramApi.sendMessage(context.chatId, "Usage: /currency <code>currency</code>, e.g. /currency EUR")
            return
        }
        groupRepository.updateCurrency(context.groupId, code)
        telegramApi.sendMessage(context.chatId, "Currency updated:\n\nThis group's default currency is now $code.")
    }
}
