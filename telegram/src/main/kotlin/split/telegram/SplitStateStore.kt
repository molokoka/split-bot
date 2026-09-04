package split.telegram

import split.core.GroupId
import split.core.MemberId
import split.core.SplitType
import java.math.BigDecimal

sealed interface SplitState

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
    val promptMessageId: Long,
) : SplitState

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
) : SplitState

class SplitStateStore {
    private val states = mutableMapOf<Long, SplitState>()

    fun get(chatId: Long): SplitState? = states[chatId]

    fun set(
        chatId: Long,
        state: SplitState,
    ) {
        states[chatId] = state
    }

    fun clear(chatId: Long) {
        states.remove(chatId)
    }
}
