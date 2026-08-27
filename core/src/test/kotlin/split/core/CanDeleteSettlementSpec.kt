package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class CanDeleteSettlementSpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val carol = MemberId("carol")

    val settlement = Settlement(
        id = SettlementId("s1"),
        groupId = group,
        currency = "USD",
        fromMemberId = bob,
        toMemberId = alice,
        amount = BigDecimal("20.00"),
        createdBy = bob,
        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
    )

    "the member who recorded the settlement can delete it" {
        canDeleteSettlement(settlement, requesterId = bob, requesterIsGroupAdmin = false) shouldBe true
    }

    "a group admin can delete someone else's settlement" {
        canDeleteSettlement(settlement, requesterId = carol, requesterIsGroupAdmin = true) shouldBe true
    }

    "a non-recorder, non-admin member cannot delete the settlement" {
        canDeleteSettlement(settlement, requesterId = carol, requesterIsGroupAdmin = false) shouldBe false
    }
})
