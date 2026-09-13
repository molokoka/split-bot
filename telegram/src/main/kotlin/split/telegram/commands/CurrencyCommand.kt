package split.telegram.commands

import split.core.GroupRepository
import split.telegram.CommandContext
import split.telegram.api.TelegramApi

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
        telegramApi.sendMessage(
            context.chatId,
            "Currency updated:\n\n" +
                "This group's default currency is now $code — new expenses use $code unless you set a " +
                "different currency inline, e.g. <code>/split 90 USD dinner @bob</code>.",
        )
    }
}
