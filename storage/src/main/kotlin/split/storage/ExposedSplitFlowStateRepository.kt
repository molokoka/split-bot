package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import split.core.GroupId
import split.core.MemberId

class ExposedSplitFlowStateRepository(
    private val db: Database,
) : SplitFlowStateRepository {
    override suspend fun upsert(row: SplitFlowStateRow): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                val updated =
                    SplitFlowStateTable.update({
                        (SplitFlowStateTable.chatId eq row.chatId) and
                            (SplitFlowStateTable.promptMessageId eq row.promptMessageId)
                    }) { it.fill(row) }
                if (updated == 0) {
                    SplitFlowStateTable.insert { it.fill(row) }
                }
            }
        }

    override suspend fun findByChatAndMessage(
        chatId: Long,
        messageId: Long,
    ): SplitFlowStateRow? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SplitFlowStateTable
                    .selectAll()
                    .where {
                        (SplitFlowStateTable.chatId eq chatId) and
                            (
                                (SplitFlowStateTable.promptMessageId eq messageId) or
                                    (SplitFlowStateTable.actionsMessageId eq messageId) or
                                    (SplitFlowStateTable.pendingPromptMessageId eq messageId)
                            )
                    }.singleOrNull()
                    ?.toRow()
            }
        }

    override suspend fun listByChat(chatId: Long): List<SplitFlowStateRow> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SplitFlowStateTable
                    .selectAll()
                    .where { SplitFlowStateTable.chatId eq chatId }
                    .map { it.toRow() }
            }
        }

    override suspend fun delete(
        chatId: Long,
        promptMessageId: Long,
    ): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SplitFlowStateTable.deleteWhere {
                    (SplitFlowStateTable.chatId eq chatId) and (SplitFlowStateTable.promptMessageId eq promptMessageId)
                }
            }
        }

    override suspend fun listSplitsByGroup(groupId: GroupId): List<SplitFlowStateRow> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SplitFlowStateTable
                    .selectAll()
                    .where {
                        (SplitFlowStateTable.groupId eq groupId.value) and
                            (SplitFlowStateTable.stateType eq SplitFlowStateType.SPLIT.name)
                    }.map { it.toRow() }
            }
        }
}

private fun UpdateBuilder<Int>.fill(row: SplitFlowStateRow) {
    this[SplitFlowStateTable.chatId] = row.chatId
    this[SplitFlowStateTable.promptMessageId] = row.promptMessageId
    this[SplitFlowStateTable.groupId] = row.groupId.value
    this[SplitFlowStateTable.stateType] = row.stateType.name
    this[SplitFlowStateTable.invokerId] = row.invokerId.value
    this[SplitFlowStateTable.description] = row.description
    this[SplitFlowStateTable.amountCents] = row.amount?.toCents()
    this[SplitFlowStateTable.currency] = row.currency
    this[SplitFlowStateTable.awaiting] = row.awaiting
    this[SplitFlowStateTable.splitTypeHint] = row.splitTypeHint
    this[SplitFlowStateTable.mentionUsernames] = row.mentionUsernames?.let(::encodeStrings)
    this[SplitFlowStateTable.exactAmounts] =
        row.exactAmounts?.let { amounts -> encodeLongs(amounts.map { it.toCents() }) }
    this[SplitFlowStateTable.stage] = row.stage
    this[SplitFlowStateTable.participantIds] =
        row.participantIds?.let { ids -> encodeStrings(ids.map { it.value }) }
    this[SplitFlowStateTable.amountsEntered] =
        row.amountsEntered?.let { map ->
            encodeAmountsByMemberId(map.entries.associate { it.key.value to it.value.toCents() })
        }
    this[SplitFlowStateTable.actionsMessageId] = row.actionsMessageId
    this[SplitFlowStateTable.pendingParticipantId] = row.pendingParticipantId?.value
    this[SplitFlowStateTable.pendingPromptMessageId] = row.pendingPromptMessageId
    this[SplitFlowStateTable.pendingIsAutoAdvance] = row.pendingIsAutoAdvance
}

private fun ResultRow.toRow(): SplitFlowStateRow =
    SplitFlowStateRow(
        chatId = this[SplitFlowStateTable.chatId],
        groupId = GroupId(this[SplitFlowStateTable.groupId]),
        stateType = SplitFlowStateType.valueOf(this[SplitFlowStateTable.stateType]),
        invokerId = MemberId(this[SplitFlowStateTable.invokerId]),
        promptMessageId = this[SplitFlowStateTable.promptMessageId],
        description = this[SplitFlowStateTable.description],
        amount = this[SplitFlowStateTable.amountCents]?.centsToAmount(),
        currency = this[SplitFlowStateTable.currency],
        awaiting = this[SplitFlowStateTable.awaiting],
        splitTypeHint = this[SplitFlowStateTable.splitTypeHint],
        mentionUsernames = this[SplitFlowStateTable.mentionUsernames]?.let(::decodeStrings),
        exactAmounts =
            this[SplitFlowStateTable.exactAmounts]?.let { text ->
                decodeLongs(text).map { it.centsToAmount() }
            },
        stage = this[SplitFlowStateTable.stage],
        participantIds =
            this[SplitFlowStateTable.participantIds]?.let { text -> decodeStrings(text).map { MemberId(it) } },
        amountsEntered =
            this[SplitFlowStateTable.amountsEntered]?.let { text ->
                decodeAmountsByMemberId(text).entries.associate { MemberId(it.key) to it.value.centsToAmount() }
            },
        actionsMessageId = this[SplitFlowStateTable.actionsMessageId],
        pendingParticipantId = this[SplitFlowStateTable.pendingParticipantId]?.let { MemberId(it) },
        pendingPromptMessageId = this[SplitFlowStateTable.pendingPromptMessageId],
        pendingIsAutoAdvance = this[SplitFlowStateTable.pendingIsAutoAdvance],
    )
