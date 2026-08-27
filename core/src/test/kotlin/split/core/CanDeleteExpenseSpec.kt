package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class CanDeleteExpenseSpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val carol = MemberId("carol")

    val dinner = Expense(
        id = ExpenseId("e1"),
        groupId = group,
        currency = "USD",
        description = "dinner",
        amount = BigDecimal("90.00"),
        payerId = alice,
        splitType = SplitType.EQUAL,
        shares = listOf(ExpenseShare(alice, BigDecimal("90.00"))),
    )

    "the original payer can delete their own expense" {
        canDeleteExpense(dinner, requesterId = alice, requesterIsGroupAdmin = false) shouldBe true
    }

    "a group admin can delete someone else's expense" {
        canDeleteExpense(dinner, requesterId = bob, requesterIsGroupAdmin = true) shouldBe true
    }

    "a non-payer, non-admin member cannot delete the expense" {
        canDeleteExpense(dinner, requesterId = carol, requesterIsGroupAdmin = false) shouldBe false
    }
})
