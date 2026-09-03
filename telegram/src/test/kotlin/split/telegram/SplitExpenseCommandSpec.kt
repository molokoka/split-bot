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
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

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
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100) shouldBe null
            draftStore.get(-100) shouldBe null
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize <code>@stranger</code> yet — ask them to run /start with me first."
        }
    }

    "the equal keyword creates the expense immediately, no flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "equal 90 dinner @bobby"))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("45.00"),
                bobbyId to BigDecimal("45.00"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "per-mention amounts create the exact-split expense immediately, no flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby 40"))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("50.00"),
                bobbyId to BigDecimal("40.00"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "the exact keyword with no per-mention amounts jumps straight into entering amounts" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "exact 90 dinner @bobby"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
        }
    }

    "a bare /split starts the guided draft, asking for the description first" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            val draft = draftStore.get(-100)
            draft?.awaiting shouldBe SplitDraftField.DESCRIPTION
            draft?.description shouldBe null
            draft?.amount shouldBe null
            draft?.mentionUsernames shouldBe emptyList()
            telegramApi.sentForceReplyPrompts.single().second shouldBe splitDraftPromptText(SplitDraftField.DESCRIPTION, "USD")
        }
    }

    "/split dinner starts the guided draft with the description pre-filled, asking for the amount" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "dinner"))

            val draft = draftStore.get(-100)
            draft?.awaiting shouldBe SplitDraftField.AMOUNT
            draft?.description shouldBe "dinner"
        }
    }

    "/split @alice @bob starts the guided draft with participants pre-filled, asking for the description" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bobby"))

            val draft = draftStore.get(-100)
            draft?.awaiting shouldBe SplitDraftField.DESCRIPTION
            draft?.mentionUsernames shouldBe listOf("bobby")
        }
    }
})
