package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import split.core.Group
import split.core.GroupId
import split.core.GroupRepository
import split.core.MemberId

class ExposedGroupRepository(private val db: Database) : GroupRepository {

    override suspend fun create(group: Group): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            GroupTable.insert {
                it[id] = group.id.value
                it[defaultCurrency] = group.defaultCurrency
                it[createdAt] = group.createdAt.toString()
            }
        }
    }

    override suspend fun find(id: GroupId): Group? = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            GroupTable.selectAll()
                .where { GroupTable.id eq id.value }
                .map { it.toGroup() }
                .singleOrNull()
        }
    }

    override suspend fun addMember(groupId: GroupId, memberId: MemberId): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            GroupMemberTable.insert {
                it[this.groupId] = groupId.value
                it[this.memberId] = memberId.value
            }
        }
    }
}

internal fun ResultRow.toGroup() = Group(
    id = GroupId(this[GroupTable.id]),
    defaultCurrency = this[GroupTable.defaultCurrency],
    createdAt = Instant.parse(this[GroupTable.createdAt]),
)
