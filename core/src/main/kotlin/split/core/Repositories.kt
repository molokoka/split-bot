package split.core

import java.time.Instant

interface MemberRepository {
    suspend fun create(member: Member)
    suspend fun find(id: MemberId): Member?
    suspend fun findByGroup(groupId: GroupId): List<Member>
}

interface GroupRepository {
    suspend fun create(group: Group)
    suspend fun find(id: GroupId): Group?
    suspend fun addMember(groupId: GroupId, memberId: MemberId)
    suspend fun updateCurrency(id: GroupId, currency: String)
}

interface ExpenseRepository {
    suspend fun create(expense: Expense)
    suspend fun find(id: ExpenseId): Expense?
    suspend fun listActive(groupId: GroupId): List<Expense>
    suspend fun softDelete(id: ExpenseId, deletedAt: Instant)
}

interface SettlementRepository {
    suspend fun create(settlement: Settlement)
    suspend fun listActive(groupId: GroupId): List<Settlement>
    suspend fun softDelete(id: SettlementId, deletedAt: Instant)
}

interface PlatformDirectory {
    suspend fun findMember(platform: String, externalUserId: String): MemberId?
    suspend fun linkMember(platform: String, externalUserId: String, memberId: MemberId)
    suspend fun findMemberByUsername(platform: String, username: String): MemberId?
    suspend fun setUsername(platform: String, externalUserId: String, username: String)
    suspend fun findGroup(platform: String, externalChatId: String): GroupId?
    suspend fun linkGroup(platform: String, externalChatId: String, groupId: GroupId)

    // Members without a linked platform identity, or whose identity has no username set,
    // are simply absent from the returned map — callers fall back to the member's display
    // name for those. A member is only missing entirely once they've never interacted with
    // the bot; a null/absent username means they're known but haven't set one in Telegram.
    suspend fun findUsernames(platform: String, memberIds: List<MemberId>): Map<MemberId, String>
}
