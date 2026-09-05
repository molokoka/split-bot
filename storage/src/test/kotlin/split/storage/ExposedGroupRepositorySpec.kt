package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import java.time.Instant

class ExposedGroupRepositorySpec :
    StringSpec({

        "creates and finds a group" {
            withTestDatabase { db ->
                val repo = ExposedGroupRepository(db)
                val group =
                    Group(
                        id = GroupId("g1"),
                        defaultCurrency = "USD",
                        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    )

                repo.create(group)

                repo.find(GroupId("g1")) shouldBe group
            }
        }

        "returns null for a group that doesn't exist" {
            withTestDatabase { db ->
                ExposedGroupRepository(db).find(GroupId("missing")) shouldBe null
            }
        }

        "adding a member to a group makes them findable via MemberRepository.findByGroup" {
            withTestDatabase { db ->
                val groupRepo = ExposedGroupRepository(db)
                val memberRepo = ExposedMemberRepository(db)

                val group = Group(GroupId("g1"), "USD", Instant.parse("2026-08-27T00:00:00Z"))
                val alice = Member(MemberId("alice"), "Alice")
                val bob = Member(MemberId("bob"), "Bob")

                groupRepo.create(group)
                memberRepo.create(alice)
                memberRepo.create(bob)
                groupRepo.addMember(group.id, alice.id)

                memberRepo.findByGroup(group.id) shouldBe listOf(alice)
            }
        }

        "updateCurrency changes a group's default currency" {
            withTestDatabase { db ->
                val repo = ExposedGroupRepository(db)
                val group = Group(GroupId("g1"), "USD", Instant.parse("2026-08-27T00:00:00Z"))
                repo.create(group)

                repo.updateCurrency(GroupId("g1"), "EUR")

                repo.find(GroupId("g1")) shouldBe group.copy(defaultCurrency = "EUR")
            }
        }
    })
