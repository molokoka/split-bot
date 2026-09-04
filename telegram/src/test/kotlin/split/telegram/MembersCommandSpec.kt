package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class MembersCommandSpec :
    StringSpec({

        "lists no members when the group is empty" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()
                val command = MembersCommand(ExposedMemberRepository(db), platformDirectory, telegramApi)

                command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "No members yet.")
            }
        }

        "lists members already in the group by display name when no username is known" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val memberId = resolver.resolveMember("1", null, "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, memberId)
                val telegramApi = FakeTelegramApi()
                val command = MembersCommand(memberRepository, platformDirectory, telegramApi)

                command.handle(CommandContext(-100, memberId, "1", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "Members:\n\n• Alice")
            }
        }

        "lists members by @username, preferred over their display name, once one is known" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val memberId = resolver.resolveMember("1", "alice_w", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, memberId)
                val telegramApi = FakeTelegramApi()
                val command = MembersCommand(memberRepository, platformDirectory, telegramApi)

                command.handle(CommandContext(-100, memberId, "1", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "Members:\n\n• @alice_w")
            }
        }
    })
