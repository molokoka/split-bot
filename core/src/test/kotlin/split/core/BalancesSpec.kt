package split.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BalancesSpec : StringSpec({

    val group = GroupId("g1")
    val alice = MemberId("alice")
    val bob = MemberId("bob")
    val carol = MemberId("carol")
    val createdAt = Instant.parse("2026-08-27T00:00:00Z")

    "nets balances across multiple expenses and a partial settlement" {
        val dinner = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = createdAt,
            shares = listOf(
                ExpenseShare(alice, BigDecimal("30.00")),
                ExpenseShare(bob, BigDecimal("30.00")),
                ExpenseShare(carol, BigDecimal("30.00")),
            ),
        )
        val coffee = Expense(
            id = ExpenseId("e2"),
            groupId = group,
            currency = "USD",
            description = "coffee",
            amount = BigDecimal("30.00"),
            payerId = bob,
            splitType = SplitType.EQUAL,
            createdBy = bob,
            createdAt = createdAt,
            shares = listOf(
                ExpenseShare(bob, BigDecimal("15.00")),
                ExpenseShare(carol, BigDecimal("15.00")),
            ),
        )
        val settlement = Settlement(
            id = SettlementId("s1"),
            groupId = group,
            currency = "USD",
            fromMemberId = carol,
            toMemberId = alice,
            amount = BigDecimal("20.00"),
            createdBy = carol,
            createdAt = createdAt,
        )

        val balances = computeBalances(
            currency = "USD",
            expenses = listOf(dinner, coffee),
            settlements = listOf(settlement),
        )

        balances shouldBe mapOf(
            alice to BigDecimal("40.00"),
            bob to BigDecimal("-15.00"),
            carol to BigDecimal("-25.00"),
        )
    }

    "excludes soft-deleted expenses from the balance" {
        val dinner = Expense(
            id = ExpenseId("e1"),
            groupId = group,
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice,
            splitType = SplitType.EQUAL,
            createdBy = alice,
            createdAt = createdAt,
            shares = listOf(
                ExpenseShare(alice, BigDecimal("30.00")),
                ExpenseShare(bob, BigDecimal("30.00")),
                ExpenseShare(carol, BigDecimal("30.00")),
            ),
        )
        val deletedCoffee = Expense(
            id = ExpenseId("e2"),
            groupId = group,
            currency = "USD",
            description = "coffee",
            amount = BigDecimal("30.00"),
            payerId = bob,
            splitType = SplitType.EQUAL,
            createdBy = bob,
            createdAt = createdAt,
            shares = listOf(
                ExpenseShare(bob, BigDecimal("15.00")),
                ExpenseShare(carol, BigDecimal("15.00")),
            ),
            deletedAt = Instant.parse("2026-08-27T01:00:00Z"),
        )

        val balances = computeBalances(
            currency = "USD",
            expenses = listOf(dinner, deletedCoffee),
            settlements = emptyList(),
        )

        balances shouldBe mapOf(
            alice to BigDecimal("60.00"),
            bob to BigDecimal("-30.00"),
            carol to BigDecimal("-30.00"),
        )
    }

    "excludes soft-deleted settlements from the balance" {
        val settlement = Settlement(
            id = SettlementId("s1"),
            groupId = group,
            currency = "USD",
            fromMemberId = bob,
            toMemberId = alice,
            amount = BigDecimal("20.00"),
            createdBy = bob,
            createdAt = createdAt,
            deletedAt = Instant.parse("2026-08-27T01:00:00Z"),
        )

        val balances = computeBalances(
            currency = "USD",
            expenses = emptyList(),
            settlements = listOf(settlement),
        )

        balances shouldBe emptyMap()
    }
})
