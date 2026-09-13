package split.storage

import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

enum class SplitFlowStateType { DRAFT, SPLIT }

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

    suspend fun findByChatAndMessage(
        chatId: Long,
        messageId: Long,
    ): SplitFlowStateRow?

    suspend fun listByChat(chatId: Long): List<SplitFlowStateRow>

    suspend fun delete(
        chatId: Long,
        promptMessageId: Long,
    )

    suspend fun listSplitsByGroup(groupId: GroupId): List<SplitFlowStateRow>
}
