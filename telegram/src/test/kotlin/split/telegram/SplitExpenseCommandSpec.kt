package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitExpenseCommandSpec : StringSpec({

    "starts a split-mode choice flow for the sender and mentioned members" {
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
            val flowStore = SplitFlowStore()
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            telegramApi.sentMessages.single() shouldBe (-100L to SPLIT_MODE_PROMPT)
            telegramApi.sentKeyboards.single() shouldBe splitModeKeyboard()

            val flow = flowStore.get(-100)
            flow?.invokerId shouldBe aliceId
            flow?.groupId shouldBe groupId
            flow?.amount shouldBe BigDecimal("90.00")
            flow?.currency shouldBe "USD"
            flow?.description shouldBe "dinner"
            flow?.participantIds shouldBe listOf(aliceId, bobbyId)
            flow?.stage shouldBe SplitFlowStage.CHOOSING_MODE
            flow?.promptMessageId shouldBe 1L
        }
    }

    "replies with an error and doesn't start a flow for an unrecognized mention" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100) shouldBe null
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
