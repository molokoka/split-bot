package split.telegram

import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SplitType
import split.core.resolveEqualSplit
import split.core.resolveExactSplit
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SplitFlowCallbackHandler(
    private val flowStore: SplitFlowStore,
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CallbackContext) {
        val flow = flowStore.get(context.chatId)
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.messageId != flow.promptMessageId && context.messageId != flow.actionsMessageId) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.memberId != flow.invokerId) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "Only the person who started this split can do that.", showAlert = true)
            return
        }

        when {
            context.data == SPLIT_MODE_EQUAL_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseEqual(context, flow)
            context.data == SPLIT_MODE_EXACT_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseExact(context, flow)
            context.data.startsWith(SPLIT_PICK_PREFIX) && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> pickParticipant(context, flow)
            context.data == SPLIT_CANCEL_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> cancel(context, flow)
            context.data == SPLIT_CONFIRM_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> confirm(context, flow)
            else -> telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
        }
    }

    private suspend fun chooseEqual(context: CallbackContext, flow: PendingSplit) {
        val (members, usernames) = membersAndUsernames(flow)
        val shares = resolveEqualSplit(flow.amount, flow.invokerId, flow.participantIds)
        val expense = createExpense(flow, SplitType.EQUAL, shares)
        flowStore.clear(context.chatId)

        telegramApi.editMessageText(context.chatId, flow.promptMessageId, formatExpenseConfirmation(expense, members, usernames))
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun chooseExact(context: CallbackContext, flow: PendingSplit) {
        val (members, usernames) = membersAndUsernames(flow)
        val nameOf = members.associateBy { it.id }

        telegramApi.editRichMessage(
            context.chatId,
            flow.promptMessageId,
            splitAmountsTable(flow.participantIds, emptyMap(), nameOf, usernames, flow.currency),
            splitParticipantKeyboard(flow.participantIds, emptyMap(), nameOf, usernames, flow.currency),
        )
        val actionsMessageId = telegramApi.sendMessage(
            context.chatId,
            splitActionsText(emptyMap(), flow.amount, flow.currency),
            splitActionsKeyboard(canConfirm = false),
        )
        val firstParticipantId = flow.participantIds.first()
        val promptMessageId = sendParticipantAmountPrompt(context.chatId, firstParticipantId, members, usernames, telegramApi)
        flowStore.set(
            context.chatId,
            flow.copy(
                stage = SplitFlowStage.ENTERING_AMOUNTS,
                actionsMessageId = actionsMessageId,
                pendingParticipantId = firstParticipantId,
                pendingPromptMessageId = promptMessageId,
            ),
        )
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun pickParticipant(context: CallbackContext, flow: PendingSplit) {
        val index = context.data.removePrefix(SPLIT_PICK_PREFIX).toIntOrNull()
        val memberId = index?.let { flow.participantIds.getOrNull(it) }
        if (memberId == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        val (members, usernames) = membersAndUsernames(flow)
        val promptMessageId = sendParticipantAmountPrompt(context.chatId, memberId, members, usernames, telegramApi)
        flowStore.set(context.chatId, flow.copy(pendingParticipantId = memberId, pendingPromptMessageId = promptMessageId))
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun cancel(context: CallbackContext, flow: PendingSplit) {
        telegramApi.editMessageText(context.chatId, flow.promptMessageId, "Split cancelled.")
        flow.actionsMessageId?.let { telegramApi.editMessageText(context.chatId, it, "Split cancelled.") }
        flowStore.clear(context.chatId)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun confirm(context: CallbackContext, flow: PendingSplit) {
        val shares = try {
            resolveExactSplit(flow.amount, flow.amountsEntered)
        } catch (e: IllegalArgumentException) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, e.message ?: "Amounts don't add up yet.", showAlert = true)
            return
        }
        val (members, usernames) = membersAndUsernames(flow)
        val expense = createExpense(flow, SplitType.EXACT, shares)
        flowStore.clear(context.chatId)

        telegramApi.editMessageText(context.chatId, flow.promptMessageId, formatExpenseConfirmation(expense, members, usernames))
        flow.actionsMessageId?.let { telegramApi.editMessageText(context.chatId, it, formatExpenseConfirmation(expense, members, usernames)) }
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun membersAndUsernames(flow: PendingSplit): Pair<List<split.core.Member>, Map<split.core.MemberId, String>> {
        val members = memberRepository.findByGroup(flow.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        return members to usernames
    }

    private suspend fun createExpense(flow: PendingSplit, splitType: SplitType, shares: List<split.core.ExpenseShare>): Expense {
        val expense = Expense(
            id = ExpenseId(idGenerator()),
            groupId = flow.groupId,
            currency = flow.currency,
            description = flow.description,
            amount = flow.amount,
            payerId = flow.invokerId,
            splitType = splitType,
            createdBy = flow.invokerId,
            createdAt = Instant.now(clock),
            shares = shares,
        )
        expenseRepository.create(expense)
        return expense
    }
}
