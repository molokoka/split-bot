package split.telegram.splitflow

import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.telegram.IdentityResolver
import split.telegram.ReplyContext
import split.telegram.api.TelegramApi
import split.telegram.extractMentions

private val amountWithOptionalCurrency = Regex("^(\\d+(?:\\.\\d+)?)(?:\\s+([A-Z]{3}))?$")

class SplitDraftReplyHandler(
    private val splitStateStore: SplitStateStore,
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
    private val flowStarter: SplitFlowStarter,
) {
    suspend fun handle(context: ReplyContext) {
        val draft = splitStateStore.find(context.chatId, context.replyToMessageId) as? PendingSplitDraft ?: return
        if (context.memberId != draft.invokerId) return

        when (draft.awaiting) {
            SplitDraftField.DESCRIPTION -> handleDescription(context, draft)
            SplitDraftField.AMOUNT -> handleAmount(context, draft)
            SplitDraftField.PARTICIPANTS -> handleParticipants(context, draft)
        }
    }

    private suspend fun handleDescription(
        context: ReplyContext,
        draft: PendingSplitDraft,
    ) {
        val description = context.text.trim()
        if (description.isEmpty()) {
            telegramApi.sendMessage(context.chatId, "That's empty — reply with a short description, e.g. \"dinner\".")
            return
        }
        advance(context, draft.copy(description = description))
    }

    private suspend fun handleAmount(
        context: ReplyContext,
        draft: PendingSplitDraft,
    ) {
        val match = amountWithOptionalCurrency.matchEntire(context.text.trim())
        val amount = match?.groupValues?.get(1)?.toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            telegramApi.sendMessage(
                context.chatId,
                "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, optionally followed by a currency, e.g. 42.50 or 42.50 EUR.",
            )
            return
        }
        val currency = match.groupValues[2].ifBlank { draft.currency }
        advance(context, draft.copy(amount = amount, currency = currency))
    }

    private suspend fun handleParticipants(
        context: ReplyContext,
        draft: PendingSplitDraft,
    ) {
        val mentions = extractMentions(context.text)
        if (mentions.isEmpty()) {
            telegramApi.sendMessage(context.chatId, "Mention at least one participant, e.g. <code>@alice @bob</code>.")
            return
        }
        advance(context, draft.copy(mentionUsernames = mentions))
    }

    private suspend fun advance(
        context: ReplyContext,
        updated: PendingSplitDraft,
    ) {
        val nextField =
            when {
                updated.description == null -> SplitDraftField.DESCRIPTION
                updated.amount == null -> SplitDraftField.AMOUNT
                updated.mentionUsernames.isEmpty() -> SplitDraftField.PARTICIPANTS
                else -> null
            }
        if (nextField != null) {
            val knownUsernames =
                if (nextField == SplitDraftField.PARTICIPANTS) {
                    knownUsernamesExcluding(memberRepository, platformDirectory, updated.groupId, updated.invokerId)
                } else {
                    emptyList()
                }
            val promptMessageId =
                telegramApi.sendForceReplyPrompt(
                    context.chatId,
                    splitDraftPromptText(nextField, updated.currency, knownUsernames),
                )
            splitStateStore.clear(context.chatId, updated)
            splitStateStore.set(context.chatId, updated.copy(awaiting = nextField, promptMessageId = promptMessageId))
            return
        }

        val mentionedMemberIds = mutableListOf<MemberId>()
        for (username in updated.mentionUsernames) {
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
        splitStateStore.clear(context.chatId, updated)
        flowStarter.start(
            chatId = context.chatId,
            invokerId = updated.invokerId,
            groupId = updated.groupId,
            description = updated.description!!,
            amount = updated.amount!!,
            currency = updated.currency,
            mentionedMemberIds = mentionedMemberIds,
            exactAmounts = updated.exactAmounts,
            splitTypeHint = updated.splitTypeHint,
        )
    }
}
