package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import java.math.BigDecimal

class SplitFlowCallbackHandlerSpec :
    StringSpec({

        "choosing Equal creates an equal-split expense and clears the flow" {
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
                resolver.ensureGroupMembership(groupId, bobId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.CHOOSING_MODE,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("45.00"),
                        bobId to BigDecimal("45.00"),
                    )
                telegramApi.editedMessages.single().let { (chatId, messageId, _) ->
                    chatId shouldBe -100L
                    messageId shouldBe 1L
                }
                splitStateStore.get(-100) shouldBe null
            }
        }

        "choosing Exact switches the table message and sends an actions message" {
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
                resolver.ensureGroupMembership(groupId, bobId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.CHOOSING_MODE,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EXACT_DATA))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                telegramApi.editedRichMessages.single().let { (chatId, messageId, _) ->
                    chatId shouldBe -100L
                    messageId shouldBe 1L
                }
                telegramApi.sentMessages.single().first shouldBe -100L

                val flow = splitStateStore.get(-100) as? PendingSplit
                flow?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
                flow?.actionsMessageId shouldBe 1L
                flow?.amountsEntered shouldBe emptyMap()
            }
        }

        "picking a participant records them as pending on the flow" {
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
                resolver.ensureGroupMembership(groupId, bobId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                        actionsMessageId = 2,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, splitPickData(1)))

                val flow = splitStateStore.get(-100) as? PendingSplit
                flow?.pendingParticipantId shouldBe bobId
                flow?.pendingPromptMessageId shouldBe 1L
                telegramApi.sentForceReplyPrompts.single() shouldBe
                    (-100L to "How much is @bob's share? Reply to this message with an amount.")
            }
        }

        "picking a different participant deletes the previous, still-unanswered prompt" {
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
                resolver.ensureGroupMembership(groupId, bobId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                        actionsMessageId = 2,
                        pendingParticipantId = aliceId,
                        pendingPromptMessageId = 99,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, splitPickData(1)))

                telegramApi.deletedMessages.single() shouldBe (-100L to 99L)
                (splitStateStore.get(-100) as? PendingSplit)?.pendingParticipantId shouldBe bobId
            }
        }

        "confirm creates the exact-split expense once amounts are entered" {
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
                resolver.ensureGroupMembership(groupId, bobId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                        actionsMessageId = 2,
                        amountsEntered = mapOf(aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00")),
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 2, SPLIT_CONFIRM_DATA))

                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("50.00"),
                        bobId to BigDecimal("40.00"),
                    )
                splitStateStore.get(-100) shouldBe null
            }
        }

        "cancel clears the flow without creating an expense" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                        actionsMessageId = 2,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 2, SPLIT_CANCEL_DATA))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                splitStateStore.get(-100) shouldBe null
            }
        }

        "cancel deletes an outstanding, still-unanswered prompt" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                        actionsMessageId = 2,
                        pendingParticipantId = aliceId,
                        pendingPromptMessageId = 99,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 2, SPLIT_CANCEL_DATA))

                telegramApi.deletedMessages.single() shouldBe (-100L to 99L)
            }
        }

        "a callback from someone other than the invoker is rejected" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobId = resolver.resolveMember("2", "bob", "Bob")
                val groupId = resolver.resolveGroup("-100")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId, bobId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.CHOOSING_MODE,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, bobId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                (splitStateStore.get(-100) as? PendingSplit)?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                telegramApi.answeredCallbacks.single().third shouldBe true
            }
        }

        "a callback against a stale, superseded flow's message is rejected" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                splitStateStore.set(
                    -100,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("30.00"),
                        currency = "USD",
                        description = "coffee",
                        participantIds = listOf(aliceId),
                        promptMessageId = 5,
                        stage = SplitFlowStage.CHOOSING_MODE,
                    ),
                )
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                (splitStateStore.get(-100) as? PendingSplit)?.promptMessageId shouldBe 5
                telegramApi.answeredCallbacks.single().third shouldBe true
            }
        }

        "a callback for a chat with no pending flow is answered but ignored" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val handler =
                    SplitFlowCallbackHandler(
                        splitStateStore,
                        memberRepository,
                        expenseRepository,
                        platformDirectory,
                        telegramApi,
                    )

                handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

                telegramApi.answeredCallbacks.single().first shouldBe "cbq1"
                expenseRepository.listActive(groupId) shouldBe emptyList()
            }
        }
    })
