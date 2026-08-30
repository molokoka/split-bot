package split.telegram

import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SplitType
import split.core.resolveEqualSplit
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AddExpenseCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val identityResolver: IdentityResolver,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val parsed = try {
            parseAddArgs(context.args, group.defaultCurrency)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /add usage")
            return
        }

        val participantIds = mutableListOf(context.memberId)
        for (username in parsed.mentionUsernames) {
            val participantId = platformDirectory.findMemberByUsername(IdentityResolver.PLATFORM, username)
            if (participantId == null) {
                telegramApi.sendMessage(
                    context.chatId,
                    "I don't recognize @$username yet — ask them to run /start with me first.",
                )
                return
            }
            participantIds += participantId
        }

        val uniqueParticipantIds = participantIds.distinct()
        for (participantId in uniqueParticipantIds) {
            identityResolver.ensureGroupMembership(context.groupId, participantId)
        }

        val shares = resolveEqualSplit(parsed.amount, context.memberId, uniqueParticipantIds)
        val expense = Expense(
            id = ExpenseId(idGenerator()),
            groupId = context.groupId,
            currency = parsed.currency,
            description = parsed.description,
            amount = parsed.amount,
            payerId = context.memberId,
            splitType = SplitType.EQUAL,
            createdBy = context.memberId,
            createdAt = Instant.now(clock),
            shares = shares,
        )
        expenseRepository.create(expense)

        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatExpenseConfirmation(expense, members))
    }
}
