package split.telegram.splitflow

import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.ExpenseShare
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SplitType
import split.core.resolveEqualSplit
import split.core.resolveExactSplit
import split.telegram.IdentityResolver
import split.telegram.api.TelegramApi
import split.telegram.formatAmount
import split.telegram.formatExpenseConfirmation
import split.telegram.mentionName
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal suspend fun knownUsernamesExcluding(
    memberRepository: MemberRepository,
    platformDirectory: PlatformDirectory,
    groupId: GroupId,
    excluding: MemberId,
): List<String> {
    val members = memberRepository.findByGroup(groupId).filter { it.id != excluding }
    val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
    return members.mapNotNull { usernames[it.id] }
}

internal suspend fun sendParticipantAmountPrompt(
    chatId: Long,
    memberId: MemberId,
    members: List<Member>,
    usernames: Map<MemberId, String>,
    telegramApi: TelegramApi,
): Long {
    val name = mentionName(members.associateBy { it.id }.getValue(memberId), usernames)
    return telegramApi.sendForceReplyPrompt(chatId, splitAmountPromptText(name))
}

class SplitFlowStarter(
    private val splitStateStore: SplitStateStore,
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun start(
        chatId: Long,
        invokerId: MemberId,
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        mentionedMemberIds: List<MemberId>,
        exactAmounts: List<BigDecimal>?,
        splitTypeHint: SplitType?,
    ) {
        val scaledAmount = amount.setScale(2)
        val scaledExactAmounts = exactAmounts?.map { it.setScale(2) }
        val participantIds = (listOf(invokerId) + mentionedMemberIds).distinct()

        when {
            scaledExactAmounts != null ->
                createExact(chatId, invokerId, groupId, description, scaledAmount, currency, mentionedMemberIds, scaledExactAmounts)
            splitTypeHint == SplitType.EQUAL ->
                createEqual(chatId, invokerId, groupId, description, scaledAmount, currency, participantIds)
            splitTypeHint == SplitType.EXACT ->
                startEnteringAmounts(chatId, invokerId, groupId, description, scaledAmount, currency, participantIds)
            else ->
                startChoosingMode(chatId, invokerId, groupId, description, scaledAmount, currency, participantIds)
        }
    }

    private suspend fun createEqual(
        chatId: Long,
        invokerId: MemberId,
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        participantIds: List<MemberId>,
    ) {
        val shares = resolveEqualSplit(amount, invokerId, participantIds)
        val (members, usernames) = membersAndUsernames(groupId)
        val expense = createExpense(groupId, description, amount, currency, invokerId, SplitType.EQUAL, shares)
        telegramApi.sendMessage(chatId, formatExpenseConfirmation(expense, members, usernames))
    }

    private suspend fun createExact(
        chatId: Long,
        invokerId: MemberId,
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        mentionedMemberIds: List<MemberId>,
        exactAmounts: List<BigDecimal>,
    ) {
        val mentionedShares = mentionedMemberIds.zip(exactAmounts).toMap()
        val mentionedTotal = mentionedShares.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        val invokerShare = amount - mentionedTotal
        if (invokerShare.signum() < 0) {
            telegramApi.sendMessage(
                chatId,
                "The amounts given (${formatAmount(
                    mentionedTotal,
                    currency,
                )}) add up to more than the total (${formatAmount(amount, currency)}).",
            )
            return
        }
        val shares = resolveExactSplit(amount, mentionedShares + (invokerId to invokerShare))
        val (members, usernames) = membersAndUsernames(groupId)
        val expense = createExpense(groupId, description, amount, currency, invokerId, SplitType.EXACT, shares)
        telegramApi.sendMessage(chatId, formatExpenseConfirmation(expense, members, usernames))
    }

    private suspend fun startEnteringAmounts(
        chatId: Long,
        invokerId: MemberId,
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        participantIds: List<MemberId>,
    ) {
        val (members, usernames) = membersAndUsernames(groupId)
        val nameOf = members.associateBy { it.id }
        val tableMessageId =
            telegramApi.sendRichMessage(
                chatId,
                splitAmountsTable(participantIds, emptyMap(), nameOf, usernames, currency),
                splitParticipantKeyboard(participantIds, emptyMap(), nameOf, usernames, currency),
            )
        val actionsMessageId =
            telegramApi.sendMessage(
                chatId,
                splitActionsText(emptyMap(), amount, currency),
                splitActionsKeyboard(canConfirm = false),
            )
        val firstParticipantId = participantIds.first()
        val promptMessageId = sendParticipantAmountPrompt(chatId, firstParticipantId, members, usernames, telegramApi)
        splitStateStore.set(
            chatId,
            PendingSplit(
                invokerId = invokerId,
                groupId = groupId,
                amount = amount,
                currency = currency,
                description = description,
                participantIds = participantIds,
                promptMessageId = tableMessageId,
                stage = SplitFlowStage.ENTERING_AMOUNTS,
                actionsMessageId = actionsMessageId,
                pendingParticipantId = firstParticipantId,
                pendingPromptMessageId = promptMessageId,
            ),
        )
    }

    private suspend fun startChoosingMode(
        chatId: Long,
        invokerId: MemberId,
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        participantIds: List<MemberId>,
    ) {
        val promptMessageId = telegramApi.sendMessage(chatId, SPLIT_MODE_PROMPT, splitModeKeyboard())
        splitStateStore.set(
            chatId,
            PendingSplit(
                invokerId = invokerId,
                groupId = groupId,
                amount = amount,
                currency = currency,
                description = description,
                participantIds = participantIds,
                promptMessageId = promptMessageId,
                stage = SplitFlowStage.CHOOSING_MODE,
            ),
        )
    }

    private suspend fun membersAndUsernames(groupId: GroupId): Pair<List<Member>, Map<MemberId, String>> {
        val members = memberRepository.findByGroup(groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        return members to usernames
    }

    private suspend fun createExpense(
        groupId: GroupId,
        description: String,
        amount: BigDecimal,
        currency: String,
        invokerId: MemberId,
        splitType: SplitType,
        shares: List<ExpenseShare>,
    ): Expense {
        val expense =
            Expense(
                id = ExpenseId(idGenerator()),
                groupId = groupId,
                currency = currency,
                description = description,
                amount = amount,
                payerId = invokerId,
                splitType = splitType,
                createdBy = invokerId,
                createdAt = Instant.now(clock),
                shares = shares,
            )
        expenseRepository.create(expense)
        return expense
    }
}
