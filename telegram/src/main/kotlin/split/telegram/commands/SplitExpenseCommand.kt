package split.telegram.commands

import split.core.GroupRepository
import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.telegram.CommandContext
import split.telegram.IdentityResolver
import split.telegram.PartialSplitArgs
import split.telegram.api.TelegramApi
import split.telegram.parseSplitArgs
import split.telegram.splitflow.PendingSplitDraft
import split.telegram.splitflow.SplitDraftField
import split.telegram.splitflow.SplitFlowStarter
import split.telegram.splitflow.SplitStateStore
import split.telegram.splitflow.knownUsernamesExcluding
import split.telegram.splitflow.splitDraftPromptText

class SplitExpenseCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val identityResolver: IdentityResolver,
    private val telegramApi: TelegramApi,
    private val splitStateStore: SplitStateStore,
    private val flowStarter: SplitFlowStarter,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val parsed =
            try {
                parseSplitArgs(context.args, group.defaultCurrency)
            } catch (e: IllegalArgumentException) {
                telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /split usage")
                return
            }

        val mentionedMemberIds = mutableListOf<MemberId>()
        for (username in parsed.mentionUsernames) {
            val participantId = platformDirectory.findMemberByUsername(IdentityResolver.PLATFORM, username)
            if (participantId == null) {
                telegramApi.sendMessage(
                    context.chatId,
                    "I don't recognize <code>@$username</code> yet — ask them to run /start with me first.",
                )
                return
            }
            mentionedMemberIds += participantId
        }
        for (participantId in (listOf(context.memberId) + mentionedMemberIds).distinct()) {
            identityResolver.ensureGroupMembership(context.groupId, participantId)
        }

        if (parsed.description == null || parsed.amount == null || parsed.mentionUsernames.isEmpty()) {
            startDraft(context, parsed)
            return
        }

        flowStarter.start(
            chatId = context.chatId,
            invokerId = context.memberId,
            groupId = context.groupId,
            description = parsed.description,
            amount = parsed.amount,
            currency = parsed.currency,
            mentionedMemberIds = mentionedMemberIds,
            exactAmounts = parsed.exactAmounts,
            splitTypeHint = parsed.splitTypeHint,
        )
    }

    private suspend fun startDraft(
        context: CommandContext,
        parsed: PartialSplitArgs,
    ) {
        val awaiting =
            when {
                parsed.description == null -> SplitDraftField.DESCRIPTION
                parsed.amount == null -> SplitDraftField.AMOUNT
                else -> SplitDraftField.PARTICIPANTS
            }
        val knownUsernames =
            if (awaiting == SplitDraftField.PARTICIPANTS) {
                knownUsernamesExcluding(memberRepository, platformDirectory, context.groupId, context.memberId)
            } else {
                emptyList()
            }
        val promptMessageId =
            telegramApi.sendForceReplyPrompt(
                context.chatId,
                splitDraftPromptText(awaiting, parsed.currency, knownUsernames),
            )
        splitStateStore.set(
            context.chatId,
            PendingSplitDraft(
                invokerId = context.memberId,
                groupId = context.groupId,
                splitTypeHint = parsed.splitTypeHint,
                description = parsed.description,
                amount = parsed.amount,
                currency = parsed.currency,
                mentionUsernames = parsed.mentionUsernames,
                exactAmounts = parsed.exactAmounts,
                awaiting = awaiting,
                promptMessageId = promptMessageId,
            ),
        )
    }
}
