package split.storage

import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

enum class SplitFlowStateType { DRAFT, SPLIT }

/**
 * One row per in-flight `/split` interaction (the draft wizard or the mode-choice/amount-entry
 * flow), keyed by (chatId, promptMessageId) — that pair never changes across a flow's lifetime,
 * so it doubles as the flow's stable identity for message-id matching. Fields only relevant to
 * one of DRAFT/SPLIT are null for the other; see the design spec's "Durable split-flow state"
 * section for why this is one table rather than one-per-variant or normalized further.
 */
data class SplitFlowStateRow(
    val chatId: Long,
    val groupId: GroupId,
    val stateType: SplitFlowStateType,
    val invokerId: MemberId,
    val promptMessageId: Long,
    val description: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val awaiting: String? = null,
    val splitTypeHint: String? = null,
    val mentionUsernames: List<String>? = null,
    val exactAmounts: List<BigDecimal>? = null,
    val stage: String? = null,
    val participantIds: List<MemberId>? = null,
    val amountsEntered: Map<MemberId, BigDecimal>? = null,
    val actionsMessageId: Long? = null,
    val pendingParticipantId: MemberId? = null,
    val pendingPromptMessageId: Long? = null,
    val pendingIsAutoAdvance: Boolean? = null,
)

interface SplitFlowStateRepository {
    suspend fun upsert(row: SplitFlowStateRow)

    /** Matches a row whose chatId is [chatId] and whose promptMessageId, actionsMessageId, or
     * pendingPromptMessageId equals [messageId] — the three message ids a flow's own messages
     * can carry. */
    suspend fun findByChatAndMessage(
        chatId: Long,
        messageId: Long,
    ): SplitFlowStateRow?

    /** Every row (any state type, any stage) currently open in [chatId]. */
    suspend fun listByChat(chatId: Long): List<SplitFlowStateRow>

    suspend fun delete(
        chatId: Long,
        promptMessageId: Long,
    )

    /** SPLIT-type rows for [groupId], any stage. */
    suspend fun listSplitsByGroup(groupId: GroupId): List<SplitFlowStateRow>
}
