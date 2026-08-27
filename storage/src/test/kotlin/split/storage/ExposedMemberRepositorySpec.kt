package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId

class ExposedMemberRepositorySpec : StringSpec({

    "creates and finds a member" {
        withTestDatabase { db ->
            val repo = ExposedMemberRepository(db)
            val member = Member(MemberId("alice"), "Alice")

            repo.create(member)

            repo.find(MemberId("alice")) shouldBe member
        }
    }

    "returns null for a member that doesn't exist" {
        withTestDatabase { db ->
            ExposedMemberRepository(db).find(MemberId("missing")) shouldBe null
        }
    }

    "findByGroup returns an empty list when no members are in the group" {
        withTestDatabase { db ->
            val groupRepo = ExposedGroupRepository(db)
            groupRepo.create(
                Group(
                    id = GroupId("g1"),
                    defaultCurrency = "USD",
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                ),
            )

            ExposedMemberRepository(db).findByGroup(GroupId("g1")) shouldBe emptyList()
        }
    }
})
