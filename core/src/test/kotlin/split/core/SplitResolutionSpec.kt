package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class SplitResolutionSpec : StringSpec({

    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val carol = MemberId("carol")

    "equal split divides evenly among all participants" {
        val shares = resolveEqualSplit(
            amount = BigDecimal("90.00"),
            payerId = alice,
            participantIds = listOf(alice, bob, carol),
        )

        shares shouldBe listOf(
            ExpenseShare(alice, BigDecimal("30.00")),
            ExpenseShare(bob, BigDecimal("30.00")),
            ExpenseShare(carol, BigDecimal("30.00")),
        )
    }

    "equal split rounds down per share and gives leftover cents to the payer" {
        val shares = resolveEqualSplit(
            amount = BigDecimal("10.00"),
            payerId = alice,
            participantIds = listOf(alice, bob, carol),
        )

        shares shouldBe listOf(
            ExpenseShare(alice, BigDecimal("3.34")),
            ExpenseShare(bob, BigDecimal("3.33")),
            ExpenseShare(carol, BigDecimal("3.33")),
        )

        shares.sumOf { it.shareAmount } shouldBe BigDecimal("10.00")
    }
})
