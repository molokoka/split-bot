package split.telegram.commands

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.telegram.CommandContext
import split.telegram.IdentityResolver
import split.telegram.api.TelegramApi
import split.telegram.buildExpenseListMessage
import split.telegram.splitflow.SplitStateStore
import split.telegram.splitflow.buildPendingSplitsMessage
import split.telegram.splitflow.pendingSplitsKeyboard

class ExpensesCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
    private val splitStateStore: SplitStateStore,
) {
    suspend fun handle(context: CommandContext) {
        groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        if (context.args.trim() == "pending") {
            handlePending(context)
            return
        }

        val expenses =
            expenseRepository
                .listActive(context.groupId)
                .sortedBy { it.createdAt }
                .takeLast(10)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(context.chatId, buildExpenseListMessage(expenses, members, usernames))
    }

    private suspend fun handlePending(context: CommandContext) {
        val flows = splitStateStore.listOpenSplits(context.groupId)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(
            context.chatId,
            buildPendingSplitsMessage(flows, members, usernames),
            if (flows.isEmpty()) null else pendingSplitsKeyboard(flows),
        )
    }
}
