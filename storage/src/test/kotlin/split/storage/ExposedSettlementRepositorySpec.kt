package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.Settlement
import split.core.SettlementId

class ExposedSettlementRepositorySpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val createdAt = Instant.parse("2026-08-27T00:00:00Z")

    suspend fun seedGroupAndMembers(db: Database) {
        ExposedGroupRepository(db).create(Group(group, "USD", createdAt))
        ExposedMemberRepository(db).create(Member(alice, "Alice"))
        ExposedMemberRepository(db).create(Member(bob, "Bob"))
    }

    "creates a settlement and lists it as active" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val repo = ExposedSettlementRepository(db)
            val settlement = Settlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "USD",
                fromMemberId = bob,
                toMemberId = alice,
                amount = BigDecimal("20.00"),
                createdBy = bob,
                createdAt = createdAt,
            )

            repo.create(settlement)

            repo.listActive(group) shouldBe listOf(settlement)
        }
    }

    "excludes soft-deleted settlements from listActive" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val repo = ExposedSettlementRepository(db)
            val settlement = Settlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "USD",
                fromMemberId = bob,
                toMemberId = alice,
                amount = BigDecimal("20.00"),
                createdBy = bob,
                createdAt = createdAt,
            )
            repo.create(settlement)

            repo.softDelete(settlement.id, Instant.parse("2026-08-27T01:00:00Z"))

            repo.listActive(group) shouldBe emptyList()
        }
    }

    "includes settlements regardless of currency" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val repo = ExposedSettlementRepository(db)
            val eurSettlement = Settlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "EUR",
                fromMemberId = bob,
                toMemberId = alice,
                amount = BigDecimal("20.00"),
                createdBy = bob,
                createdAt = createdAt,
            )
            repo.create(eurSettlement)

            repo.listActive(group) shouldBe listOf(eurSettlement)
        }
    }

    "round-trips an amount with cents through the INTEGER-cents encoding" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val repo = ExposedSettlementRepository(db)
            val settlement = Settlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "USD",
                fromMemberId = bob,
                toMemberId = alice,
                amount = BigDecimal("19.99"),
                createdBy = bob,
                createdAt = createdAt,
            )

            repo.create(settlement)

            repo.listActive(group).single().amount shouldBe BigDecimal("19.99")
        }
    }
})
