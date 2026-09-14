package split.telegram

import split.core.GroupId
import split.core.MemberId

class FakeIdentityResolver : IdentityResolving {
    val resolvedMemberIds = mutableListOf<String>()
    val resolvedGroupIds = mutableListOf<String>()
    val groupMemberships = mutableSetOf<Pair<GroupId, MemberId>>()

    override suspend fun resolveMember(
        externalUserId: String,
        username: String?,
        displayName: String,
    ): MemberId {
        resolvedMemberIds += externalUserId
        return MemberId(externalUserId)
    }

    override suspend fun resolveGroup(externalChatId: String): GroupId {
        resolvedGroupIds += externalChatId
        return GroupId(externalChatId)
    }

    override suspend fun ensureGroupMembership(
        groupId: GroupId,
        memberId: MemberId,
    ) {
        groupMemberships += groupId to memberId
    }
}
