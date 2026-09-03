package split.telegram

import split.core.MemberRepository
import split.core.PlatformDirectory

class SplitFlowReplyHandler(
    private val flowStore: SplitFlowStore,
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: ReplyContext) {
        val flow = flowStore.get(context.chatId) ?: return
        if (flow.stage != SplitFlowStage.ENTERING_AMOUNTS) return
        if (context.replyToMessageId != flow.pendingPromptMessageId) return
        if (context.memberId != flow.invokerId) return
        val pendingParticipantId = flow.pendingParticipantId ?: return

        val amount = context.text.trim().toBigDecimalOrNull()
        if (amount == null || amount.scale() > 2 || amount.signum() <= 0) {
            telegramApi.sendMessage(context.chatId, "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50.")
            return
        }

        val updatedAmounts = flow.amountsEntered + (pendingParticipantId to amount)
        val updatedFlow = flow.copy(amountsEntered = updatedAmounts, pendingParticipantId = null, pendingPromptMessageId = null)
        flowStore.set(context.chatId, updatedFlow)

        val members = memberRepository.findByGroup(flow.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        val nameOf = members.associateBy { it.id }

        telegramApi.editRichMessage(
            context.chatId,
            flow.promptMessageId,
            splitAmountsTable(flow.participantIds, updatedAmounts, nameOf, usernames, flow.currency),
            splitParticipantKeyboard(flow.participantIds, updatedAmounts, nameOf, usernames, flow.currency),
        )
        val canConfirm = splitIsReadyToConfirm(flow.participantIds, updatedAmounts, flow.amount)
        flow.actionsMessageId?.let {
            telegramApi.editMessageText(
                context.chatId,
                it,
                splitActionsText(updatedAmounts, flow.amount, flow.currency),
                splitActionsKeyboard(canConfirm),
            )
        }
    }
}
