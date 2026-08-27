package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId

class ExposedPlatformDirectorySpec : StringSpec({

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
})
