package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory

class ListCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .sortedByDescending { it.createdAt }
            .take(10)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(context.chatId, buildExpenseListMessage(expenses, members, usernames))
    }
}
