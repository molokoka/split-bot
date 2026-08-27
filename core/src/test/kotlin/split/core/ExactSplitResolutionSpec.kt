package split.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ExactSplitResolutionSpec : StringSpec({

    val alice = MemberId("alice")
    val bob = MemberId("bob")

    "exact split assigns exactly the entered amounts" {
        val shares = resolveExactSplit(
            amount = BigDecimal("90.00"),
            amounts = linkedMapOf(alice to BigDecimal("50.00"), bob to BigDecimal("40.00")),
        )

        shares shouldBe listOf(
            ExpenseShare(alice, BigDecimal("50.00")),
            ExpenseShare(bob, BigDecimal("40.00")),
        )
    }

    "exact split rejects entered amounts that don't sum to the total" {
        shouldThrow<IllegalArgumentException> {
            resolveExactSplit(
                amount = BigDecimal("90.00"),
                amounts = linkedMapOf(alice to BigDecimal("50.00"), bob to BigDecimal("30.00")),
            )
        }
    }
})
