package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class AddExpenseCommandSpec : StringSpec({

    "logs an equal-split expense between the sender and mentioned members" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob") // Bob has run a command before, so @bobby resolves

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))

            val expense = expenseRepository.listActive(groupId, "USD").single()
            expense.amount shouldBe BigDecimal("90.00")
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("45.00"),
                bobbyId to BigDecimal("45.00"),
            )

            telegramApi.sentMessages.single().first shouldBe -100L
        }
    }

    "replies with an error and doesn't create an expense for an unrecognized mention" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
