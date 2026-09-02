package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BalanceHistorySpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")

    "tracks each member's running balance after every expense and settlement, oldest first" {
        val dinner = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("45.00")), ExpenseShare(bob, BigDecimal("45.00"))),
        )
        val settlement = createSettlement(
            id = SettlementId("s1"),
            groupId = group,
            currency = "USD",
            from = bob,
            to = alice,
            amount = BigDecimal("45.00"),
            createdBy = bob,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
        )

        val history = computeBalanceHistory("USD", expenses = listOf(dinner), settlements = listOf(settlement))

        history shouldBe listOf(
            ExpenseHistoryEvent(dinner, mapOf(alice to BigDecimal("45.00"), bob to BigDecimal("-45.00"))),
            SettlementHistoryEvent(settlement, mapOf(alice to BigDecimal("0.00"), bob to BigDecimal("0.00"))),
        )
    }

    "orders events chronologically regardless of input order" {
        val older = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "lunch",
            amount = BigDecimal("10.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("10.00"))),
        )
        val newer = Expense(
            id = ExpenseId("e2"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("20.00"),
            payerId = bob,
            splitType = SplitType.EQUAL,
            createdBy = bob,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(bob, BigDecimal("20.00"))),
        )

        val history = computeBalanceHistory("USD", expenses = listOf(newer, older), settlements = emptyList())

        history.map { it.createdAt } shouldBe listOf(older.createdAt, newer.createdAt)
    }

    "ignores expenses and settlements in a different currency" {
        val eurExpense = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "EUR",
            description = "dinner",
            amount = BigDecimal("10.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("10.00"))),
        )

        computeBalanceHistory("USD", expenses = listOf(eurExpense), settlements = emptyList()) shouldBe emptyList()
    }

    "ignores soft-deleted expenses and settlements" {
        val deletedExpense = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("10.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("10.00"))),
            deletedAt = Instant.parse("2026-08-27T01:00:00Z"),
        )

        computeBalanceHistory("USD", expenses = listOf(deletedExpense), settlements = emptyList()) shouldBe emptyList()
    }

    "the all-currencies overload merges each currency's independent ledger, sorted chronologically" {
        val dinner = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("45.00")), ExpenseShare(bob, BigDecimal("45.00"))),
        )
        val taxi = Expense(
            id = ExpenseId("e2"),
            groupId = group,
            currency = "EUR",
            description = "taxi",
            amount = BigDecimal("20.00"),
            payerId = bob,
            splitType = SplitType.EQUAL,
            createdBy = bob,
            createdAt = Instant.parse("2026-08-27T12:00:00Z"),
            shares = listOf(ExpenseShare(alice, BigDecimal("10.00")), ExpenseShare(bob, BigDecimal("10.00"))),
        )

        val history = computeBalanceHistory(expenses = listOf(dinner, taxi), settlements = emptyList())

        history shouldBe listOf(
            ExpenseHistoryEvent(dinner, mapOf(alice to BigDecimal("45.00"), bob to BigDecimal("-45.00"))),
            ExpenseHistoryEvent(taxi, mapOf(bob to BigDecimal("10.00"), alice to BigDecimal("-10.00"))),
        )
    }
})
