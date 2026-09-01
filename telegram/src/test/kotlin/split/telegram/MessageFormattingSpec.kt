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

    "mentionName prefers a clickable @username over the display name when one is known" {
        mentionName(alice, mapOf(alice.id to "alice_w")) shouldBe "@alice_w"
    }

    "mentionName falls back to the escaped display name when no username is known" {
        mentionName(alice, emptyMap()) shouldBe "Alice"
        mentionName(Member(MemberId("m1"), "<script>"), emptyMap()) shouldBe "&lt;script&gt;"
    }

    "formatExpenseConfirmation names participants by @username once known, falling back per-member otherwise" {
        val expense = Expense(
            id = ExpenseId("e1"),
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

        formatExpenseConfirmation(expense, members, usernames = mapOf(alice.id to "alice_w")) shouldBe
            "@alice_w paid 90.00 USD for <b>dinner</b>, split equally with Bob"
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

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 90.00 EUR for <b>dinner</b>, split equally with Bob"
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

        formatExpenseConfirmation(exact, members) shouldBe "Alice paid 90.00 USD for <b>dinner</b>, split by exact amounts with Bob"
        formatExpenseConfirmation(shares, members) shouldBe "Alice paid 90.00 USD for <b>dinner</b>, split by shares with Bob"
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

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 12.00 USD for <b>solo lunch</b>"
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

    "formatExpenseList shows a short id, date, and per-person share breakdown for an equal split" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T14:30:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<code>abcdef12</code>  2026-08-28  <b>dinner</b>  90.00 USD\n" +
            "paid by Alice, split equally: Alice 45.00 USD, Bob 45.00 USD"
    }

    "formatExpenseList shows the real per-person amounts for an exact split, not an equal guess" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "rent",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EXACT,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<code>abcdef12</code>  2026-08-28  <b>rent</b>  90.00 USD\n" +
            "paid by Alice, split by exact amounts: Alice 50.00 USD, Bob 40.00 USD"
    }

    "formatExpenseList shows the real per-person amounts for a shares split" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "groceries",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.SHARES,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("60.00")), ExpenseShare(bob.id, BigDecimal("30.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<code>abcdef12</code>  2026-08-28  <b>groceries</b>  90.00 USD\n" +
            "paid by Alice, split by shares: Alice 60.00 USD, Bob 30.00 USD"
    }

    "formatExpenseList explains there's nothing yet" {
        formatExpenseList(emptyList(), members) shouldBe "No expenses yet — use /add to log one."
    }

    "formatBalances phrases payments relative to the viewer" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatBalances(payments, members, viewerId = alice.id, currency = "USD") shouldBe "Bob owes you 30.00 USD"
        formatBalances(payments, members, viewerId = bob.id, currency = "USD") shouldBe "You owe Alice 30.00 USD"
    }

    "formatBalances names a member by @username once one is known" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatBalances(payments, members, viewerId = alice.id, currency = "USD", usernames = mapOf(bob.id to "bobby")) shouldBe
            "@bobby owes you 30.00 USD"
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

    "formatSettleSuggestions names members by @username once known" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatSettleSuggestions(
            payments, members, currency = "USD",
            usernames = mapOf(bob.id to "bobby", alice.id to "alice_w"),
        ) shouldBe "@bobby pays @alice_w 30.00 USD"
    }

    "formatSettleSuggestions says everyone's settled up when there's nothing to do" {
        formatSettleSuggestions(emptyList(), members, currency = "USD") shouldBe "Everyone's settled up — nothing to do!"
    }
})
