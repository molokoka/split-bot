package split.telegram

import split.core.GroupId
import split.core.MemberId
import split.core.SplitType
import split.storage.SplitFlowStateRepository
import split.storage.SplitFlowStateRow
import split.storage.SplitFlowStateType
import java.math.BigDecimal

sealed interface SplitState {
    val promptMessageId: Long
}

enum class SplitDraftField { DESCRIPTION, AMOUNT, PARTICIPANTS }

data class PendingSplitDraft(
    val invokerId: MemberId,
    val groupId: GroupId,
    val splitTypeHint: SplitType?,
    val description: String?,
    val amount: BigDecimal?,
    val currency: String,
    val mentionUsernames: List<String>,
    val exactAmounts: List<BigDecimal>?,
    val awaiting: SplitDraftField,
    override val promptMessageId: Long,
) : SplitState

enum class SplitFlowStage { CHOOSING_MODE, ENTERING_AMOUNTS }

data class PendingSplit(
    val invokerId: MemberId,
    val groupId: GroupId,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val participantIds: List<MemberId>,
    override val promptMessageId: Long,
    val stage: SplitFlowStage,
    val actionsMessageId: Long? = null,
    val amountsEntered: Map<MemberId, BigDecimal> = emptyMap(),
    val pendingParticipantId: MemberId? = null,
    val pendingPromptMessageId: Long? = null,
    val pendingIsAutoAdvance: Boolean = true,
) : SplitState

class SplitStateStore(
    private val repository: SplitFlowStateRepository,
) {
    suspend fun find(
        chatId: Long,
        messageId: Long,
    ): SplitState? = repository.findByChatAndMessage(chatId, messageId)?.toSplitState()

    suspend fun set(
        chatId: Long,
        state: SplitState,
    ) {
        repository.upsert(state.toRow(chatId))
    }

    suspend fun clear(
        chatId: Long,
        state: SplitState,
    ) {
        repository.delete(chatId, state.promptMessageId)
    }

    /** Every flow/draft currently open in [chatId] — used where a caller doesn't already know
     * which message id to look up by (test introspection today; nothing in production code
     * needs this, since a real update always carries a concrete message id). */
    suspend fun listAll(chatId: Long): List<SplitState> = repository.listByChat(chatId).map { it.toSplitState() }

    suspend fun listOpenSplits(groupId: GroupId): List<PendingSplit> =
        repository
            .listSplitsByGroup(groupId)
            .map { it.toSplitState() as PendingSplit }
            .filter { it.stage == SplitFlowStage.ENTERING_AMOUNTS }
}

private fun SplitFlowStateRow.toSplitState(): SplitState =
    when (stateType) {
        SplitFlowStateType.DRAFT ->
            PendingSplitDraft(
                invokerId = invokerId,
                groupId = groupId,
                splitTypeHint = splitTypeHint?.let { SplitType.valueOf(it) },
                description = description,
                amount = amount,
                currency = currency ?: error("DRAFT row missing currency"),
                mentionUsernames = mentionUsernames ?: emptyList(),
                exactAmounts = exactAmounts,
                awaiting = SplitDraftField.valueOf(awaiting ?: error("DRAFT row missing awaiting")),
                promptMessageId = promptMessageId,
            )
        SplitFlowStateType.SPLIT ->
            PendingSplit(
                invokerId = invokerId,
                groupId = groupId,
                amount = amount ?: error("SPLIT row missing amount"),
                currency = currency ?: error("SPLIT row missing currency"),
                description = description ?: error("SPLIT row missing description"),
                participantIds = participantIds ?: emptyList(),
                promptMessageId = promptMessageId,
                stage = SplitFlowStage.valueOf(stage ?: error("SPLIT row missing stage")),
                actionsMessageId = actionsMessageId,
                amountsEntered = amountsEntered ?: emptyMap(),
                pendingParticipantId = pendingParticipantId,
                pendingPromptMessageId = pendingPromptMessageId,
                pendingIsAutoAdvance = pendingIsAutoAdvance ?: true,
            )
    }

private fun SplitState.toRow(chatId: Long): SplitFlowStateRow =
    when (this) {
        is PendingSplitDraft ->
            SplitFlowStateRow(
                chatId = chatId,
                groupId = groupId,
                stateType = SplitFlowStateType.DRAFT,
                invokerId = invokerId,
                promptMessageId = promptMessageId,
                description = description,
                amount = amount,
                currency = currency,
                awaiting = awaiting.name,
                splitTypeHint = splitTypeHint?.name,
                mentionUsernames = mentionUsernames,
                exactAmounts = exactAmounts,
            )
        is PendingSplit ->
            SplitFlowStateRow(
                chatId = chatId,
                groupId = groupId,
                stateType = SplitFlowStateType.SPLIT,
                invokerId = invokerId,
                promptMessageId = promptMessageId,
                description = description,
                amount = amount,
                currency = currency,
                stage = stage.name,
                participantIds = participantIds,
                amountsEntered = amountsEntered,
                actionsMessageId = actionsMessageId,
                pendingParticipantId = pendingParticipantId,
                pendingPromptMessageId = pendingPromptMessageId,
                pendingIsAutoAdvance = pendingIsAutoAdvance,
            )
    }
