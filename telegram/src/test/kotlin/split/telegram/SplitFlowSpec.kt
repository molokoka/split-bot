package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import java.math.BigDecimal

class SplitFlowSpec :
    StringSpec({

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
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val callbackHandler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
                val promptMessageId = (splitStateStore.get(-100) as PendingSplit).promptMessageId

                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EQUAL_DATA))

                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("45.00"),
                        bobbyId to BigDecimal("45.00"),
                    )
                splitStateStore.get(-100) shouldBe null
            }
        }

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
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val callbackHandler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )
                val replyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)

                // /split 90 dinner @bobby
                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
                val promptMessageId = (splitStateStore.get(-100) as PendingSplit).promptMessageId

                // Tap "Exact" (button lives on the mode-choice message, i.e. promptMessageId)
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))

                // Tap Alice's button (on the table message) — pops a dedicated ForceReply prompt
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
                val alicePromptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, alicePromptId, "50"))

                // Not confirmable yet — only one of two participants has an amount
                (splitStateStore.get(-100) as? PendingSplit)?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
                expenseRepository.listActive(groupId) shouldBe emptyList()

                // Tap Bob's button, reply "40"
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
                val bobPromptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, bobPromptId, "40"))

                // Tap "Confirm" — the actions message migrates (deleted + resent) on every reply,
                // so its id must be re-read now rather than reusing the one captured earlier.
                val latestActionsMessageId = (splitStateStore.get(-100) as PendingSplit).actionsMessageId!!
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", latestActionsMessageId, SPLIT_CONFIRM_DATA))

                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("50.00"),
                        bobId to BigDecimal("40.00"),
                    )
                splitStateStore.get(-100) shouldBe null
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
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val callbackHandler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )
                val replyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
                val promptMessageId = (splitStateStore.get(-100) as PendingSplit).promptMessageId

                // Tap "Exact" — no participant tap follows; the first prompt (Alice's) is already pending.
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
                (splitStateStore.get(-100) as? PendingSplit)?.pendingParticipantId shouldBe aliceId
                var promptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!

                // Reply directly — no tap for Bob either; the flow auto-advances to him.
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptId, "50"))
                (splitStateStore.get(-100) as? PendingSplit)?.pendingParticipantId shouldBe bobId
                promptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!

                // Reply directly for the last participant — no next prompt, ready to confirm.
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptId, "40"))
                (splitStateStore.get(-100) as? PendingSplit)?.pendingParticipantId shouldBe null
                (splitStateStore.get(-100) as? PendingSplit)?.pendingPromptMessageId shouldBe null

                // The actions message migrates (deleted + resent) on every reply, so its id must
                // be re-read now rather than the one captured right after choosing Exact.
                val latestActionsMessageId = (splitStateStore.get(-100) as PendingSplit).actionsMessageId!!
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", latestActionsMessageId, SPLIT_CONFIRM_DATA))

                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("50.00"),
                        bobId to BigDecimal("40.00"),
                    )
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
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val callbackHandler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )
                val replyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
                val promptMessageId = (splitStateStore.get(-100) as PendingSplit).promptMessageId
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
                val actionsMessageId = (splitStateStore.get(-100) as PendingSplit).actionsMessageId!!

                // Enter Alice at 50, then Bob at 40 — total 90, ready to confirm.
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
                val aliceFirstPromptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, aliceFirstPromptId, "50"))
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
                val bobPromptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, bobPromptId, "40"))
                splitIsReadyToConfirm(
                    listOf(aliceId, bobId),
                    (splitStateStore.get(-100) as PendingSplit).amountsEntered,
                    BigDecimal("90.00"),
                ) shouldBe
                    true

                // Tap Alice again and change her amount to 60 — now over budget, not confirmable.
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", promptMessageId, splitPickData(0)))
                val aliceSecondPromptId = (splitStateStore.get(-100) as PendingSplit).pendingPromptMessageId!!
                replyHandler.handle(ReplyContext(-100, aliceId, groupId, aliceSecondPromptId, "60"))

                (splitStateStore.get(-100) as? PendingSplit)?.amountsEntered shouldBe
                    mapOf(aliceId to BigDecimal("60"), bobId to BigDecimal("40"))
                splitIsReadyToConfirm(
                    listOf(aliceId, bobId),
                    (splitStateStore.get(-100) as PendingSplit).amountsEntered,
                    BigDecimal("90.00"),
                ) shouldBe
                    false
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
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val callbackHandler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "30 coffee @bobby"))
                val firstPromptMessageId = (splitStateStore.get(-100) as PendingSplit).promptMessageId

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))
                val secondFlow = (splitStateStore.get(-100) as PendingSplit)
                secondFlow.promptMessageId shouldBe firstPromptMessageId + 1
                secondFlow.description shouldBe "dinner"

                // A tap on the first (now stale) message is rejected, not applied to the new flow.
                callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", firstPromptMessageId, SPLIT_MODE_EQUAL_DATA))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                (splitStateStore.get(-100) as? PendingSplit)?.description shouldBe "dinner"
            }
        }
    })
