package split.telegram.commands

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SettlementRepository
import split.core.computeBalanceHistory
import split.telegram.CommandContext
import split.telegram.IdentityResolver
import split.telegram.api.TelegramApi
import split.telegram.buildHistoryMessage

class HistoryCommand(
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
        val events = computeBalanceHistory(expenses, settlements).takeLast(20)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(context.chatId, buildHistoryMessage(events, members, usernames))
    }
}
