package split.telegram

import split.core.Group
import split.core.GroupId
import split.core.GroupRepository
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory
import java.time.Clock
import java.time.Instant
import java.util.UUID

class IdentityResolver(
    private val platformDirectory: PlatformDirectory,
    private val memberRepository: MemberRepository,
    private val groupRepository: GroupRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun resolveMember(
        externalUserId: String,
        username: String?,
        displayName: String,
    ): MemberId {
        val existing = platformDirectory.findMember(PLATFORM, externalUserId)
        val memberId =
            if (existing != null) {
                existing
            } else {
                val newId = MemberId(idGenerator())
                memberRepository.create(Member(newId, displayName))
                platformDirectory.linkMember(PLATFORM, externalUserId, newId)
                newId
            }
        if (username != null) {
            platformDirectory.setUsername(PLATFORM, externalUserId, username)
        }
        return memberId
    }

    suspend fun resolveGroup(externalChatId: String): GroupId {
        val existing = platformDirectory.findGroup(PLATFORM, externalChatId)
        if (existing != null) return existing

        val newId = GroupId(idGenerator())
        groupRepository.create(Group(newId, DEFAULT_CURRENCY, Instant.now(clock)))
        platformDirectory.linkGroup(PLATFORM, externalChatId, newId)
        return newId
    }

    suspend fun ensureGroupMembership(
        groupId: GroupId,
        memberId: MemberId,
    ) {
        val members = memberRepository.findByGroup(groupId)
        if (members.none { it.id == memberId }) {
            groupRepository.addMember(groupId, memberId)
        }
    }

    companion object {
        const val PLATFORM = "telegram"
        const val DEFAULT_CURRENCY = "USD"
    }
}
