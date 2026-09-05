package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import java.time.Instant

class ExposedPlatformDirectorySpec :
    StringSpec({

        "links and finds a member by platform identity" {
            withTestDatabase { db ->
                ExposedMemberRepository(db).create(Member(MemberId("alice"), "Alice"))
                val directory = ExposedPlatformDirectory(db)

                directory.linkMember("telegram", "123456", MemberId("alice"))

                directory.findMember("telegram", "123456") shouldBe MemberId("alice")
            }
        }

        "returns null for an unknown platform identity" {
            withTestDatabase { db ->
                ExposedPlatformDirectory(db).findMember("telegram", "unknown") shouldBe null
            }
        }

        "links and finds a group by platform chat id" {
            withTestDatabase { db ->
                ExposedGroupRepository(db).create(
                    Group(GroupId("g1"), "USD", Instant.parse("2026-08-27T00:00:00Z")),
                )
                val directory = ExposedPlatformDirectory(db)

                directory.linkGroup("telegram", "-100999", GroupId("g1"))

                directory.findGroup("telegram", "-100999") shouldBe GroupId("g1")
            }
        }

        "returns null for an unknown platform chat id" {
            withTestDatabase { db ->
                ExposedPlatformDirectory(db).findGroup("telegram", "unknown") shouldBe null
            }
        }

        "finds a member by username after it's been set" {
            withTestDatabase { db ->
                ExposedMemberRepository(db).create(Member(MemberId("alice"), "Alice"))
                val directory = ExposedPlatformDirectory(db)
                directory.linkMember("telegram", "123456", MemberId("alice"))

                directory.setUsername("telegram", "123456", "alice_w")

                directory.findMemberByUsername("telegram", "alice_w") shouldBe MemberId("alice")
            }
        }

        "returns null for an unknown username" {
            withTestDatabase { db ->
                ExposedPlatformDirectory(db).findMemberByUsername("telegram", "nobody") shouldBe null
            }
        }

        "findUsernames returns only members with a linked, non-null username" {
            withTestDatabase { db ->
                val memberRepository = ExposedMemberRepository(db)
                memberRepository.create(Member(MemberId("alice"), "Alice"))
                memberRepository.create(Member(MemberId("bob"), "Bob"))
                memberRepository.create(Member(MemberId("carol"), "Carol"))
                val directory = ExposedPlatformDirectory(db)
                directory.linkMember("telegram", "1", MemberId("alice"))
                directory.setUsername("telegram", "1", "alice_w")
                directory.linkMember("telegram", "2", MemberId("bob"))
                // carol never linked a platform identity at all

                val usernames =
                    directory.findUsernames(
                        "telegram",
                        listOf(MemberId("alice"), MemberId("bob"), MemberId("carol")),
                    )

                usernames shouldBe mapOf(MemberId("alice") to "alice_w")
            }
        }

        "findUsernames returns an empty map for an empty member list" {
            withTestDatabase { db ->
                ExposedPlatformDirectory(db).findUsernames("telegram", emptyList()) shouldBe emptyMap()
            }
        }
    })
