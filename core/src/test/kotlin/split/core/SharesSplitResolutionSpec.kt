package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class SharesSplitResolutionSpec : StringSpec({

    val alice = MemberId("alice")
    val bob = MemberId("bob")

    "shares split divides proportionally to each member's share count" {
        val shares = resolveSharesSplit(
            amount = BigDecimal("90.00"),
            payerId = alice,
            shareCounts = linkedMapOf(alice to 2, bob to 1),
        )

        shares shouldBe listOf(
            ExpenseShare(alice, BigDecimal("60.00")),
            ExpenseShare(bob, BigDecimal("30.00")),
        )
    }
})
