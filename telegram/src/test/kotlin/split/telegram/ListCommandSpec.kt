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

    "lists active expenses newest first, capped at 10" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            fun expense(id: String, description: String, at: String) = Expense(
                id = ExpenseId(id),
                groupId = groupId,
                currency = "USD",
                description = description,
                amount = BigDecimal("10.00"),
                payerId = aliceId,
                splitType = SplitType.EQUAL,
                createdBy = aliceId,
                createdAt = Instant.parse(at),
                shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00"))),
            )

            // ids are unrelated to their descriptions on purpose, to make it obvious in the
            // expected output below which part is the 8-char id prefix vs. the description
            expenseRepository.create(expense("older1234567890", "lunch", "2026-08-27T00:00:00Z"))
            expenseRepository.create(expense("newer1234567890", "dinner", "2026-08-28T00:00:00Z"))

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "[newer123] dinner — 10.00 USD\n[older123] lunch — 10.00 USD",
            )
        }
    }

    "explains there's nothing yet" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "No expenses yet — use /add to log one.")
        }
    }
})
