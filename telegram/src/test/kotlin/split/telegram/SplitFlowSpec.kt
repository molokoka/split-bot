package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitFlowSpec : StringSpec({

    "the full exact-split flow: choose Exact, enter both amounts, confirm" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val draftStore = SplitDraftStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, memberRepository, resolver, telegramApi, draftStore, flowStarter)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)
            val replyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            // /split 90 dinner @bobby
            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId

            // Tap "Exact" (button lives on the mode-choice message, i.e. promptMessageId)
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
            val actionsMessageId = flowStore.get(-100)!!.actionsMessageId!!

            // Tap Alice's button (on the table message) — pops a dedicated ForceReply prompt
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
            val alicePromptId = flowStore.get(-100)!!.pendingPromptMessageId!!
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, alicePromptId, "50"))

            // Not confirmable yet — only one of two participants has an amount
            flowStore.get(-100)?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
            expenseRepository.listActive(groupId) shouldBe emptyList()

            // Tap Bob's button, reply "40"
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
            val bobPromptId = flowStore.get(-100)!!.pendingPromptMessageId!!
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, bobPromptId, "40"))

            // Tap "Confirm" (button lives on the actions message)
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", actionsMessageId, SPLIT_CONFIRM_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("50.00"),
                bobId to BigDecimal("40.00"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "choosing Exact auto-advances through participants with no manual taps needed" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val draftStore = SplitDraftStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, memberRepository, resolver, telegramApi, draftStore, flowStarter)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)
            val replyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId

            // Tap "Exact" — no participant tap follows; the first prompt (Alice's) is already pending.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
            val actionsMessageId = flowStore.get(-100)!!.actionsMessageId!!
            flowStore.get(-100)?.pendingParticipantId shouldBe aliceId
            var promptId = flowStore.get(-100)!!.pendingPromptMessageId!!

            // Reply directly — no tap for Bob either; the flow auto-advances to him.
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptId, "50"))
            flowStore.get(-100)?.pendingParticipantId shouldBe bobId
            promptId = flowStore.get(-100)!!.pendingPromptMessageId!!

            // Reply directly for the last participant — no next prompt, ready to confirm.
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptId, "40"))
            flowStore.get(-100)?.pendingParticipantId shouldBe null
            flowStore.get(-100)?.pendingPromptMessageId shouldBe null

            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", actionsMessageId, SPLIT_CONFIRM_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("50.00"),
                bobId to BigDecimal("40.00"),
            )
        }
    }

    "the full equal-split flow: choose Equal, expense created immediately" {
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
            val flowStore = SplitFlowStore()
            val draftStore = SplitDraftStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(
                platformDirectory, groupRepository, memberRepository, resolver, telegramApi, draftStore, flowStarter,
            )
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId

            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EQUAL_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("45.00"),
                bobbyId to BigDecimal("45.00"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "re-entering a participant's amount before confirming overwrites the earlier value" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobId = resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val draftStore = SplitDraftStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, memberRepository, resolver, telegramApi, draftStore, flowStarter)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)
            val replyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
            val actionsMessageId = flowStore.get(-100)!!.actionsMessageId!!

            // Enter Alice at 50, then Bob at 40 — total 90, ready to confirm.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
            val aliceFirstPromptId = flowStore.get(-100)!!.pendingPromptMessageId!!
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, aliceFirstPromptId, "50"))
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
            val bobPromptId = flowStore.get(-100)!!.pendingPromptMessageId!!
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, bobPromptId, "40"))
            splitIsReadyToConfirm(listOf(aliceId, bobId), flowStore.get(-100)!!.amountsEntered, BigDecimal("90.00")) shouldBe true

            // Tap Alice again and change her amount to 60 — now over budget, not confirmable.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", promptMessageId, splitPickData(0)))
            val aliceSecondPromptId = flowStore.get(-100)!!.pendingPromptMessageId!!
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, aliceSecondPromptId, "60"))

            flowStore.get(-100)?.amountsEntered shouldBe mapOf(aliceId to BigDecimal("60"), bobId to BigDecimal("40"))
            splitIsReadyToConfirm(listOf(aliceId, bobId), flowStore.get(-100)!!.amountsEntered, BigDecimal("90.00")) shouldBe false
        }
    }

    "a second /split replaces the first, still-pending flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.resolveMember("2", "bobby", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val draftStore = SplitDraftStore()
            val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, memberRepository, resolver, telegramApi, draftStore, flowStarter)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "30 coffee @bobby"))
            val firstPromptMessageId = flowStore.get(-100)!!.promptMessageId

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
            val secondFlow = flowStore.get(-100)!!
            secondFlow.promptMessageId shouldBe firstPromptMessageId + 1
            secondFlow.description shouldBe "dinner"

            // A tap on the first (now stale) message is rejected, not applied to the new flow.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", firstPromptMessageId, SPLIT_MODE_EQUAL_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.description shouldBe "dinner"
        }
    }
})
