package split.telegram.commands

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SettlementRepository
import split.core.computeBalances
import split.core.distinctCurrencies
import split.core.simplifyDebts
import split.telegram.CommandContext
import split.telegram.IdentityResolver
import split.telegram.api.TelegramApi
import split.telegram.formatSettleSuggestions

class SettleSuggestCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId)
        val settlements = settlementRepository.listActive(context.groupId)
        val paymentsByCurrency =
            distinctCurrencies(expenses, settlements).associateWith { currency ->
                simplifyDebts(computeBalances(currency, expenses, settlements))
            }
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendMessage(
            context.chatId,
            formatSettleSuggestions(paymentsByCurrency, members, usernames),
        )
    }
}
