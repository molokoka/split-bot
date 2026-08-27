package split.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class CreateSettlementSpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")

    "builds a settlement recording that one member paid another" {
        val settlement = createSettlement(
            id = SettlementId("s1"),
            groupId = group,
            currency = "USD",
            from = bob,
            to = alice,
            amount = BigDecimal("20.00"),
        )

        settlement shouldBe Settlement(
            id = SettlementId("s1"),
            groupId = group,
            currency = "USD",
            fromMemberId = bob,
            toMemberId = alice,
            amount = BigDecimal("20.00"),
        )
    }

    "rejects a zero or negative amount" {
        shouldThrow<IllegalArgumentException> {
            createSettlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "USD",
                from = bob,
                to = alice,
                amount = BigDecimal("0.00"),
            )
        }
    }

    "rejects settling with yourself" {
        shouldThrow<IllegalArgumentException> {
            createSettlement(
                id = SettlementId("s1"),
                groupId = group,
                currency = "USD",
                from = alice,
                to = alice,
                amount = BigDecimal("20.00"),
            )
        }
    }
})
