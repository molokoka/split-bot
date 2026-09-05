package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import split.core.GroupId
import split.core.MemberId
import split.core.PlatformDirectory

class ExposedPlatformDirectory(private val db: Database) : PlatformDirectory {

    override suspend fun findMember(platform: String, externalUserId: String): MemberId? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.selectAll()
                    .where {
                        (PlatformIdentityTable.platform eq platform) and
                            (PlatformIdentityTable.externalUserId eq externalUserId)
                    }
                    .map { MemberId(it[PlatformIdentityTable.memberId]) }
                    .singleOrNull()
            }
        }

    override suspend fun linkMember(platform: String, externalUserId: String, memberId: MemberId): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.insert {
                    it[this.platform] = platform
                    it[this.externalUserId] = externalUserId
                    it[this.memberId] = memberId.value
                }
            }
        }

    override suspend fun findMemberByUsername(platform: String, username: String): MemberId? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.selectAll()
                    .where {
                        (PlatformIdentityTable.platform eq platform) and
                            (PlatformIdentityTable.username eq username)
                    }
                    .map { MemberId(it[PlatformIdentityTable.memberId]) }
                    .singleOrNull()
            }
        }

    override suspend fun setUsername(platform: String, externalUserId: String, username: String): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.update({
                    (PlatformIdentityTable.platform eq platform) and
                        (PlatformIdentityTable.externalUserId eq externalUserId)
                }) {
                    it[this.username] = username
                }
            }
        }

    override suspend fun findGroup(platform: String, externalChatId: String): GroupId? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformGroupLinkTable.selectAll()
                    .where {
                        (PlatformGroupLinkTable.platform eq platform) and
                            (PlatformGroupLinkTable.externalChatId eq externalChatId)
                    }
                    .map { GroupId(it[PlatformGroupLinkTable.groupId]) }
                    .singleOrNull()
            }
        }

    override suspend fun linkGroup(platform: String, externalChatId: String, groupId: GroupId): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformGroupLinkTable.insert {
                    it[this.platform] = platform
                    it[this.externalChatId] = externalChatId
                    it[this.groupId] = groupId.value
                }
            }
        }

    override suspend fun findUsernames(platform: String, memberIds: List<MemberId>): Map<MemberId, String> =
        withContext(Dispatchers.IO) {
            if (memberIds.isEmpty()) return@withContext emptyMap()
            suspendTransaction(db) {
                PlatformIdentityTable.selectAll()
                    .where {
                        (PlatformIdentityTable.platform eq platform) and
                            (PlatformIdentityTable.memberId inList memberIds.map { it.value })
                    }
                    .mapNotNull { row ->
                        row[PlatformIdentityTable.username]?.let { username ->
                            MemberId(row[PlatformIdentityTable.memberId]) to username
                        }
                    }
                    .toMap()
            }
        }
}
