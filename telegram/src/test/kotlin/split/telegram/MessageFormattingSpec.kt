package split.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.DebtPayment
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.SplitType

class MessageFormattingSpec : StringSpec({

    val alice = Member(MemberId("alice"), "Alice")
    val bob = Member(MemberId("bob"), "Bob")
    val members = listOf(alice, bob)

    "formatAmount renders two decimal places with the currency code" {
        formatAmount(BigDecimal("90"), "USD") shouldBe "90.00 USD"
        formatAmount(BigDecimal("12.5"), "EUR") shouldBe "12.50 EUR"
    }

    "formatExpenseConfirmation names payer, amount, currency, and split type, without repeating the payer" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "EUR",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 90.00 EUR for dinner, split equally with Bob"
    }

    "formatExpenseConfirmation names the split type for EXACT and SHARES splits too" {
        val exact = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EXACT,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
        )
        val shares = exact.copy(splitType = SplitType.SHARES)

        formatExpenseConfirmation(exact, members) shouldBe "Alice paid 90.00 USD for dinner, split by exact amounts with Bob"
        formatExpenseConfirmation(shares, members) shouldBe "Alice paid 90.00 USD for dinner, split by shares with Bob"
    }

    "formatExpenseConfirmation omits the split clause entirely when the payer is the only participant" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "solo lunch",
            amount = BigDecimal("12.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("12.00"))),
        )

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 12.00 USD for solo lunch"
    }

    "formatExpenseConfirmation fails loudly if the payer isn't in the members list" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = MemberId("not-a-member"),
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("90.00"))),
        )

        shouldThrow<NoSuchElementException> { formatExpenseConfirmation(expense, members) }
    }

    "formatExpenseList shows a short id plus who paid and who participated per line" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "[abcdef12] Alice paid 90.00 USD for dinner, split equally with Bob"
    }

    "formatExpenseList explains there's nothing yet" {
        formatExpenseList(emptyList(), members) shouldBe "No expenses yet — use /add to log one."
    }

    "formatBalances phrases payments relative to the viewer" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatBalances(payments, members, viewerId = alice.id, currency = "USD") shouldBe "Bob owes you 30.00 USD"
        formatBalances(payments, members, viewerId = bob.id, currency = "USD") shouldBe "You owe Alice 30.00 USD"
    }

    "formatBalances says everyone's settled up when there's nothing relevant" {
        formatBalances(emptyList(), members, viewerId = alice.id, currency = "USD") shouldBe "You're all settled up!"
    }

    "formatBalances fails loudly if a payment references a member outside the group" {
        val payments = listOf(DebtPayment(from = MemberId("not-a-member"), to = alice.id, amount = BigDecimal("30.00")))

        shouldThrow<NoSuchElementException> { formatBalances(payments, members, viewerId = alice.id, currency = "USD") }
    }

    "formatSettleSuggestions lists every payment" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatSettleSuggestions(payments, members, currency = "USD") shouldBe "Bob pays Alice 30.00 USD"
    }

    "formatSettleSuggestions says everyone's settled up when there's nothing to do" {
        formatSettleSuggestions(emptyList(), members, currency = "USD") shouldBe "Everyone's settled up — nothing to do!"
    }
})
