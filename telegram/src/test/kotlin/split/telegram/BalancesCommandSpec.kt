package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.Settlement
import split.core.SettlementId
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class BalancesCommandSpec : StringSpec({

    "shows every member's debts, not just the caller's" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val platformDirectory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

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
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, bobId, "2", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Balances:\n\n@bob owes @alice 30.00 USD")
        }
    }

    "says everyone's settled up when there are no expenses" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val platformDirectory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Everyone's settled up!")
        }
    }

    "lists every net payment across three people, regardless of who asks" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val platformDirectory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

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
            // Bob pays $30 for drinks, split three ways: Alice and Carol each owe Bob $10,
            // which cancels the Alice<->Bob $10 IOU from dinner entirely, leaving only
            // Carol owing $10 to each of Alice and Bob.
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
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))
            command.handle(CommandContext(-100, carolId, "3", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "Balances:\n\n@carol owes @alice 10.00 USD\n@carol owes @bob 10.00 USD",
                -100L to "Balances:\n\n@carol owes @alice 10.00 USD\n@carol owes @bob 10.00 USD",
            )
        }
    }

    "shows every currency with an outstanding balance, and skips one that's already settled up" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val platformDirectory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            // USD: Bob owes Alice.
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
            // EUR: split evenly and already fully settled by the settlement below.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e2"),
                    groupId = groupId,
                    currency = "EUR",
                    description = "taxi",
                    amount = BigDecimal("20.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobId, BigDecimal("10.00"))),
                ),
            )
            settlementRepository.create(
                Settlement(
                    id = SettlementId("s1"),
                    groupId = groupId,
                    currency = "EUR",
                    fromMemberId = bobId,
                    toMemberId = aliceId,
                    amount = BigDecimal("10.00"),
                    createdBy = bobId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Balances:\n\n@bob owes @alice 30.00 USD")
        }
    }

    "lists three members' balances across two currencies in a single message" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val platformDirectory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val carolId = resolver.resolveMember("3", "carol", "Carol")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)
            resolver.ensureGroupMembership(groupId, carolId)

            // USD: Alice pays $90 for dinner. Bob owes Alice $10, Carol owes Alice $20.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = aliceId,
                    splitType = SplitType.EXACT,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(aliceId, BigDecimal("60.00")),
                        ExpenseShare(bobId, BigDecimal("10.00")),
                        ExpenseShare(carolId, BigDecimal("20.00")),
                    ),
                ),
            )
            // EUR: Bob pays €30 for a taxi with only Carol — Alice isn't part of this one.
            // Carol owes Bob €10.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e2"),
                    groupId = groupId,
                    currency = "EUR",
                    description = "taxi",
                    amount = BigDecimal("30.00"),
                    payerId = bobId,
                    splitType = SplitType.EXACT,
                    createdBy = bobId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(bobId, BigDecimal("20.00")),
                        ExpenseShare(carolId, BigDecimal("10.00")),
                    ),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "Balances:\n\n@carol owes @bob 10.00 EUR\n@carol owes @alice 20.00 USD\n@bob owes @alice 10.00 USD",
            )
        }
    }
})
