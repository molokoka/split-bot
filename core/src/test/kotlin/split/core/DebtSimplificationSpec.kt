package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class DebtSimplificationSpec : StringSpec({

    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val carol = MemberId("carol")

    "collapses mixed debts into the minimal set of payments that zero out the group" {
        val balances = mapOf(
            alice to BigDecimal("-30.00"),
            bob to BigDecimal("10.00"),
            carol to BigDecimal("20.00"),
        )

        val payments = simplifyDebts(balances)

        payments shouldBe listOf(
            DebtPayment(alice, carol, BigDecimal("20.00")),
            DebtPayment(alice, bob, BigDecimal("10.00")),
        )
    }
})
