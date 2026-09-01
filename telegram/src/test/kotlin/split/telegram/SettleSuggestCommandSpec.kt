package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class SettleSuggestCommandSpec : StringSpec({

    "suggests the minimal set of payments to settle the group" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("60.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobId, BigDecimal("30.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Bob pays Alice 30.00 USD")
        }
    }

    "says everyone's settled up when there's nothing to do" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Everyone's settled up — nothing to do!")
        }
    }

    "simplifies crisscrossing debts across three people into the full minimal payment set" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val carolId = resolver.resolveMember("3", "carol", "Carol")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)
            resolver.ensureGroupMembership(groupId, carolId)

            // Alice pays $30 for dinner, split three ways: Bob and Carol each owe Alice $10.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("30.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(aliceId, BigDecimal("10.00")),
                        ExpenseShare(bobId, BigDecimal("10.00")),
                        ExpenseShare(carolId, BigDecimal("10.00")),
                    ),
                ),
            )
            // Bob pays $30 for drinks, split three ways: Alice and Carol each owe Bob $10.
            // Cancels the Alice<->Bob $10 IOU entirely — four raw obligations across the two
            // expenses collapse to two net payments, both from Carol.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e2"),
                    groupId = groupId,
                    currency = "USD",
                    description = "drinks",
                    amount = BigDecimal("30.00"),
                    payerId = bobId,
                    splitType = SplitType.EQUAL,
                    createdBy = bobId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(aliceId, BigDecimal("10.00")),
                        ExpenseShare(bobId, BigDecimal("10.00")),
                        ExpenseShare(carolId, BigDecimal("10.00")),
                    ),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "Carol pays Alice 10.00 USD\nCarol pays Bob 10.00 USD",
            )
        }
    }
})
