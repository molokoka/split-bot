package split.telegram

import split.core.GroupRepository
import split.core.PlatformDirectory

class SplitExpenseCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val identityResolver: IdentityResolver,
    private val telegramApi: TelegramApi,
    private val flowStore: SplitFlowStore,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val parsed = try {
            parseSplitArgs(context.args, group.defaultCurrency)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /split usage")
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

        val promptMessageId = telegramApi.sendMessage(context.chatId, SPLIT_MODE_PROMPT, splitModeKeyboard())
        flowStore.set(
            context.chatId,
            PendingSplit(
                invokerId = context.memberId,
                groupId = context.groupId,
                amount = parsed.amount.setScale(2),
                currency = parsed.currency,
                description = parsed.description,
                participantIds = uniqueParticipantIds,
                promptMessageId = promptMessageId,
                stage = SplitFlowStage.CHOOSING_MODE,
            ),
        )
    }
}
