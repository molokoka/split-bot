package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Member
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class IdentityResolverSpec : StringSpec({

    "creates a new member on first sight and reuses it afterwards" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                ExposedMemberRepository(db),
                ExposedGroupRepository(db),
            )

            val first = resolver.resolveMember("123", "alice_w", "Alice")
            val second = resolver.resolveMember("123", "alice_w", "Alice")

            first shouldBe second
        }
    }

    "records the username so it can be looked up later" {
        withTestDatabase { db ->
            val directory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(directory, ExposedMemberRepository(db), ExposedGroupRepository(db))

            val memberId = resolver.resolveMember("123", "alice_w", "Alice")

            directory.findMemberByUsername("telegram", "alice_w") shouldBe memberId
        }
    }

    "creates a new group on first sight and reuses it afterwards" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                ExposedMemberRepository(db),
                ExposedGroupRepository(db),
            )

            val first = resolver.resolveGroup("-100001")
            val second = resolver.resolveGroup("-100001")

            first shouldBe second
        }
    }

    "ensureGroupMembership adds a member to the group only once" {
        withTestDatabase { db ->
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                memberRepository,
                ExposedGroupRepository(db),
            )

            val memberId = resolver.resolveMember("123", "alice_w", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            resolver.ensureGroupMembership(groupId, memberId)
            resolver.ensureGroupMembership(groupId, memberId)

            memberRepository.findByGroup(groupId) shouldBe listOf(Member(memberId, "Alice"))
        }
    }
})
