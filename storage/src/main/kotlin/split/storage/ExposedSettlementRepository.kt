package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import split.core.GroupId
import split.core.MemberId
import split.core.Settlement
import split.core.SettlementId
import split.core.SettlementRepository

class ExposedSettlementRepository(private val db: Database) : SettlementRepository {

    override suspend fun create(settlement: Settlement): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            SettlementTable.insert {
                it[id] = settlement.id.value
                it[groupId] = settlement.groupId.value
                it[currency] = settlement.currency
                it[fromMemberId] = settlement.fromMemberId.value
                it[toMemberId] = settlement.toMemberId.value
                it[amountCents] = settlement.amount.toCents()
                it[createdBy] = settlement.createdBy.value
                it[createdAt] = settlement.createdAt.toString()
                it[deletedAt] = settlement.deletedAt?.toString()
            }
        }
    }

    override suspend fun listActive(groupId: GroupId, currency: String): List<Settlement> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SettlementTable.selectAll()
                    .where {
                        (SettlementTable.groupId eq groupId.value) and
                            (SettlementTable.currency eq currency) and
                            SettlementTable.deletedAt.isNull()
                    }
                    .map { it.toSettlement() }
            }
        }

    override suspend fun softDelete(id: SettlementId, deletedAt: Instant): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            SettlementTable.update({ SettlementTable.id eq id.value }) {
                it[this.deletedAt] = deletedAt.toString()
            }
        }
    }
}

private fun ResultRow.toSettlement() = Settlement(
    id = SettlementId(this[SettlementTable.id]),
    groupId = GroupId(this[SettlementTable.groupId]),
    currency = this[SettlementTable.currency],
    fromMemberId = MemberId(this[SettlementTable.fromMemberId]),
    toMemberId = MemberId(this[SettlementTable.toMemberId]),
    amount = this[SettlementTable.amountCents].centsToAmount(),
    createdBy = MemberId(this[SettlementTable.createdBy]),
    createdAt = Instant.parse(this[SettlementTable.createdAt]),
    deletedAt = this[SettlementTable.deletedAt]?.let { Instant.parse(it) },
)
