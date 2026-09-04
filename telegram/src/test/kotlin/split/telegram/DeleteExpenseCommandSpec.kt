package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import java.math.BigDecimal
import java.time.Instant

class DeleteExpenseCommandSpec :
    StringSpec({

        "the payer can delete their own expense" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)

                val expense =
                    Expense(
                        id = ExpenseId("abcdef1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("90.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
                    )
                expenseRepository.create(expense)

                val telegramApi = FakeTelegramApi()
                val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, "abcdef12"))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                telegramApi.sentMessages shouldBe listOf(-100L to "Expense deleted:\n\n\"dinner\" (90.00 USD).")
            }
        }

        "a non-payer, non-admin cannot delete the expense" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                val expense =
                    Expense(
                        id = ExpenseId("abcdef1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("90.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
                    )
                expenseRepository.create(expense)

                val telegramApi = FakeTelegramApi()
                val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

                command.handle(CommandContext(-100, bobbyId, "2", groupId, "abcdef12"))

                expenseRepository.listActive(groupId).size shouldBe 1
                telegramApi.sentMessages shouldBe listOf(-100L to "Only the payer or a group admin can delete this expense.")
            }
        }

        "a group admin can delete someone else's expense" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                val expense =
                    Expense(
                        id = ExpenseId("abcdef1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("90.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
                    )
                expenseRepository.create(expense)

                val telegramApi = FakeTelegramApi()
                telegramApi.chatAdministrators = listOf(TgChatMember(status = "administrator", user = TgUser(id = 2, firstName = "Bob")))
                val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

                command.handle(CommandContext(-100, bobbyId, "2", groupId, "abcdef12"))

                expenseRepository.listActive(groupId) shouldBe emptyList()
            }
        }

        "can find and delete an expense logged in a currency other than the group's default" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)

                val expense =
                    Expense(
                        id = ExpenseId("eur1234567890"),
                        groupId = groupId,
                        currency = "EUR",
                        description = "hotel",
                        amount = BigDecimal("120.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("120.00"))),
                    )
                expenseRepository.create(expense)

                val telegramApi = FakeTelegramApi()
                val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, "eur12345"))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                telegramApi.sentMessages shouldBe listOf(-100L to "Expense deleted:\n\n\"hotel\" (120.00 EUR).")
            }
        }

        "replies when no expense matches the given id" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")

                val telegramApi = FakeTelegramApi()
                val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, "zzzzzzzz"))

                telegramApi.sentMessages shouldBe listOf(-100L to "No active expense found matching \"zzzzzzzz\" — check /expenses.")
            }
        }
    })
