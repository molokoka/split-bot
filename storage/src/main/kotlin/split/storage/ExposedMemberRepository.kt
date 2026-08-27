package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository

class ExposedMemberRepository(private val db: Database) : MemberRepository {

    override suspend fun create(member: Member): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            MemberTable.insert {
                it[id] = member.id.value
                it[displayName] = member.displayName
            }
        }
    }

    override suspend fun find(id: MemberId): Member? = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            MemberTable.selectAll()
                .where { MemberTable.id eq id.value }
                .map { it.toMember() }
                .singleOrNull()
        }
    }

    override suspend fun findByGroup(groupId: GroupId): List<Member> = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            (MemberTable innerJoin GroupMemberTable)
                .selectAll()
                .where { GroupMemberTable.groupId eq groupId.value }
                .map { it.toMember() }
        }
    }
}

internal fun ResultRow.toMember() = Member(
    id = MemberId(this[MemberTable.id]),
    displayName = this[MemberTable.displayName],
)
