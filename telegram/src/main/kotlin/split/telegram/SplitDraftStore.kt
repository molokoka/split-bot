package split.telegram

import split.core.GroupId
import split.core.MemberId
import split.core.SplitType
import java.math.BigDecimal

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
)

class SplitDraftStore {
    private val drafts = mutableMapOf<Long, PendingSplitDraft>()

    fun get(chatId: Long): PendingSplitDraft? = drafts[chatId]

    fun set(chatId: Long, draft: PendingSplitDraft) {
        drafts[chatId] = draft
    }

    fun clear(chatId: Long) {
        drafts.remove(chatId)
    }
}
