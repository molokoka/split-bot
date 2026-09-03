package split.telegram

import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

enum class SplitFlowStage { CHOOSING_MODE, ENTERING_AMOUNTS }

data class PendingSplit(
    val invokerId: MemberId,
    val groupId: GroupId,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val participantIds: List<MemberId>,
    val promptMessageId: Long,
    val stage: SplitFlowStage,
    val actionsMessageId: Long? = null,
    val amountsEntered: Map<MemberId, BigDecimal> = emptyMap(),
    val pendingParticipantId: MemberId? = null,
    val pendingPromptMessageId: Long? = null,
    val pendingIsAutoAdvance: Boolean = true,
)

class SplitFlowStore {
    private val flows = mutableMapOf<Long, PendingSplit>()

    fun get(chatId: Long): PendingSplit? = flows[chatId]

    fun set(chatId: Long, flow: PendingSplit) {
        flows[chatId] = flow
    }

    fun clear(chatId: Long) {
        flows.remove(chatId)
    }
}
