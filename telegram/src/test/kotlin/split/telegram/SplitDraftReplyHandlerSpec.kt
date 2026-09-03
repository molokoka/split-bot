package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitDraftReplyHandlerSpec : StringSpec({

    "completing description, amount, then participants starts the mode-choice flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
            var promptId = draftStore.get(-100)!!.promptMessageId
            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "dinner"))

            draftStore.get(-100)?.awaiting shouldBe SplitDraftField.AMOUNT
            promptId = draftStore.get(-100)!!.promptMessageId
            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90"))

            draftStore.get(-100)?.awaiting shouldBe SplitDraftField.PARTICIPANTS
            promptId = draftStore.get(-100)!!.promptMessageId
            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "@bobby"))

            draftStore.get(-100) shouldBe null
            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.stage shouldBe SplitFlowStage.CHOOSING_MODE
            flowStore.get(-100)?.participantIds shouldBe listOf(aliceId, bobbyId)
        }
    }

    "an equal keyword carried through the draft finishes without a mode-choice tap" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "equal @bobby"))
            var promptId = draftStore.get(-100)!!.promptMessageId
            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "dinner"))

            promptId = draftStore.get(-100)!!.promptMessageId
            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90"))

            draftStore.get(-100) shouldBe null
            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("45.00"),
                bobbyId to BigDecimal("45.00"),
            )
        }
    }

    "an empty description reply doesn't advance the draft" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
            val promptId = draftStore.get(-100)!!.promptMessageId

            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "   "))

            draftStore.get(-100)?.awaiting shouldBe SplitDraftField.DESCRIPTION
            draftStore.get(-100)?.description shouldBe null
        }
    }

    "an invalid amount reply doesn't advance the draft" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "dinner"))
            val promptId = draftStore.get(-100)!!.promptMessageId

            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "not a number"))

            draftStore.get(-100)?.awaiting shouldBe SplitDraftField.AMOUNT
            draftStore.get(-100)?.amount shouldBe null
        }
    }

    "an amount reply can override the currency" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "dinner"))
            val promptId = draftStore.get(-100)!!.promptMessageId

            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90 EUR"))

            draftStore.get(-100)?.amount shouldBe BigDecimal("90")
            draftStore.get(-100)?.currency shouldBe "EUR"
            draftStore.get(-100)?.awaiting shouldBe SplitDraftField.PARTICIPANTS
        }
    }

    "an unrecognized mention at the participants step errors and clears the draft" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner"))
            val promptId = draftStore.get(-100)!!.promptMessageId

            handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "@stranger"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            telegramApi.sentMessages.last().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }

    "a reply from someone other than the invoker is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val draftStore = SplitDraftStore()
            val flowStore = SplitFlowStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, draftStore, flowStarter)
            val handler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
            val draftBefore = draftStore.get(-100)

            handler.handle(ReplyContext(-100, bobId, groupId, draftBefore!!.promptMessageId, "dinner"))

            draftStore.get(-100) shouldBe draftBefore
        }
    }
})
