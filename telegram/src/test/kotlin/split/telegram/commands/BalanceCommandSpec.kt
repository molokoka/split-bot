package split.telegram.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.split
import split.telegram.withTestDatabase
import java.math.BigDecimal
import java.time.Instant

class BalanceCommandSpec :
    StringSpec({

        "shows what the viewer owes and is owed" {
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
                val command =
                    BalanceCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, bobId, "2", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "Balances:\n\nYou owe @alice 30.00 USD")
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
                val command =
                    BalanceCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "You're all settled up!")
            }
        }

        "nets balances across multiple expenses instead of showing the last one" {
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

                // Alice pays $60 for dinner, split evenly: Bob owes Alice $30.
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
                // Bob pays $20 for coffee, split evenly: Alice owes Bob $10 back,
                // partially offsetting the dinner debt. Net: Bob still owes Alice $20.
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("e2"),
                        groupId = groupId,
                        currency = "USD",
                        description = "coffee",
                        amount = BigDecimal("20.00"),
                        payerId = bobId,
                        splitType = SplitType.EQUAL,
                        createdBy = bobId,
                        createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobId, BigDecimal("10.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command =
                    BalanceCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, bobId, "2", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "Balances:\n\nYou owe @alice 20.00 USD")
            }
        }

        "simplifies crisscrossing debts across three people to each viewer's single net position" {
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
                        shares =
                            listOf(
                                ExpenseShare(aliceId, BigDecimal("10.00")),
                                ExpenseShare(bobId, BigDecimal("10.00")),
                                ExpenseShare(carolId, BigDecimal("10.00")),
                            ),
                    ),
                )
                // Bob pays $30 for drinks, split three ways: Alice and Carol each owe Bob $10.
                // This cancels out the Alice<->Bob $10 IOU from the dinner entirely, leaving
                // only Carol owing $10 to each of Alice and Bob — four raw obligations across
                // the two expenses collapse to two net payments, and Alice never sees Bob at
                // all in her balance even though they owed each other money along the way.
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
                        shares =
                            listOf(
                                ExpenseShare(aliceId, BigDecimal("10.00")),
                                ExpenseShare(bobId, BigDecimal("10.00")),
                                ExpenseShare(carolId, BigDecimal("10.00")),
                            ),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command =
                    BalanceCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))
                command.handle(CommandContext(-100, bobId, "2", groupId, ""))
                command.handle(CommandContext(-100, carolId, "3", groupId, ""))

                telegramApi.sentMessages shouldBe
                    listOf(
                        -100L to "Balances:\n\n@carol owes you 10.00 USD", // alice
                        -100L to "Balances:\n\n@carol owes you 10.00 USD", // bob
                        -100L to "Balances:\n\nYou owe @alice 10.00 USD\nYou owe @bob 10.00 USD", // carol
                    )
            }
        }

        "shows every currency the viewer has a stake in, and skips one that's already settled up" {
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
                val command =
                    BalanceCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, bobId, "2", groupId, ""))

                telegramApi.sentMessages shouldBe listOf(-100L to "Balances:\n\nYou owe @alice 30.00 USD")
            }
        }
    })
