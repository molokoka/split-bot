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

class ListCommandSpec : StringSpec({

    "lists active expenses newest first, capped at 10, naming who paid and who participated by @username" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobbyId)

            // ids are unrelated to their descriptions on purpose, to make it obvious in the
            // expected output below which part is the 8-char id prefix vs. the description
            expenseRepository.create(
                Expense(
                    id = ExpenseId("older1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "lunch",
                    amount = BigDecimal("10.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00"))),
                ),
            )
            expenseRepository.create(
                Expense(
                    id = ExpenseId("newer1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("20.00"),
                    payerId = bobbyId,
                    splitType = SplitType.EQUAL,
                    createdBy = bobbyId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobbyId, BigDecimal("10.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "<code>newer123</code>  2026-08-28  <b>dinner</b>  20.00 USD\n" +
                    "paid by @bobby, split equally: @alice 10.00 USD, @bobby 10.00 USD\n\n" +
                    "<code>older123</code>  2026-08-27  <b>lunch</b>  10.00 USD\n" +
                    "paid by @alice, split equally: @alice 10.00 USD",
            )
        }
    }

    "shows the real per-person breakdown for exact and shares splits, not just equal" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobbyId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("aaaa1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "rent",
                    amount = BigDecimal("100.00"),
                    payerId = aliceId,
                    splitType = SplitType.EXACT,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("60.00")), ExpenseShare(bobbyId, BigDecimal("40.00"))),
                ),
            )
            expenseRepository.create(
                Expense(
                    id = ExpenseId("bbbb1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "utilities",
                    amount = BigDecimal("90.00"),
                    payerId = bobbyId,
                    splitType = SplitType.SHARES,
                    createdBy = bobbyId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobbyId, BigDecimal("60.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "<code>bbbb1234</code>  2026-08-29  <b>utilities</b>  90.00 USD\n" +
                    "paid by @bobby, split by shares: @alice 30.00 USD, @bobby 60.00 USD\n\n" +
                    "<code>aaaa1234</code>  2026-08-28  <b>rent</b>  100.00 USD\n" +
                    "paid by @alice, split by exact amounts: @alice 60.00 USD, @bobby 40.00 USD",
            )
        }
    }

    "falls back to the escaped display name for a member with no known username" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", null, "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("aaaa1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "coffee",
                    amount = BigDecimal("5.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("5.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "<code>aaaa1234</code>  2026-08-28  <b>coffee</b>  5.00 USD\n" +
                    "paid by Alice, split equally: Alice 5.00 USD",
            )
        }
    }

    "explains there's nothing yet" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "No expenses yet — use /add to log one.")
        }
    }
})
