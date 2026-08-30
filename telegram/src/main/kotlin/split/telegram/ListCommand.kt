package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository

class ListCommand(
    private val groupRepository: GroupRepository,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .sortedByDescending { it.createdAt }
            .take(10)

        telegramApi.sendMessage(context.chatId, formatExpenseList(expenses))
    }
}
