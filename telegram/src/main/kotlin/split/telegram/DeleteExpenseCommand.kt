package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.canDeleteExpense
import java.time.Clock
import java.time.Instant

class DeleteExpenseCommand(
    private val groupRepository: GroupRepository,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val idPrefix = context.args.trim()
        if (idPrefix.isEmpty()) {
            telegramApi.sendMessage(context.chatId, "Usage: /delete <id> (see /list for ids)")
            return
        }

        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expense = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .firstOrNull { it.id.value.startsWith(idPrefix, ignoreCase = true) }

        if (expense == null) {
            telegramApi.sendMessage(context.chatId, "No active expense found matching \"$idPrefix\" — check /list.")
            return
        }

        val admins = telegramApi.getChatAdministrators(context.chatId)
        val isAdmin = admins.any { it.user.id.toString() == context.externalUserId }

        if (!canDeleteExpense(expense, context.memberId, isAdmin)) {
            telegramApi.sendMessage(context.chatId, "Only the payer or a group admin can delete this expense.")
            return
        }

        expenseRepository.softDelete(expense.id, Instant.now(clock))
        telegramApi.sendMessage(
            context.chatId,
            "Deleted \"${expense.description}\" (${formatAmount(expense.amount, expense.currency)}).",
        )
    }
}
