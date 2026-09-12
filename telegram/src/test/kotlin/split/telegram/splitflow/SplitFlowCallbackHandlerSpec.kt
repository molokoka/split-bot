package split.telegram.splitflow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.CallbackContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.currentFlow
import split.telegram.expenseCreatedWith
import split.telegram.split
import split.telegram.withTestDatabase
import java.math.BigDecimal

private const val CALLBACK_CHAT_ID = -100L

private class CallbackFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val handler =
        SplitFlowCallbackHandler(splitStateStore, memberRepository, expenseRepository, platformDirectory, telegramApi)
}

private suspend fun CallbackFixture.aliceAndBob(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val bobId = resolver.resolveMember("2", "bob", "Bob")
    val groupId = resolver.resolveGroup("-100")
    return Triple(aliceId, bobId, groupId)
}

private suspend fun CallbackFixture.aliceAndBobInGroup(): Triple<MemberId, MemberId, GroupId> {
    val (aliceId, bobId, groupId) = aliceAndBob()
    resolver.ensureGroupMembership(groupId, aliceId)
    resolver.ensureGroupMembership(groupId, bobId)
    return Triple(aliceId, bobId, groupId)
}

private suspend fun CallbackFixture.aliceOnly(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100")
    return aliceId to groupId
}

private fun CallbackFixture.aChoosingModeFlow(
    invokerId: MemberId,
    groupId: GroupId,
    participantIds: List<MemberId>,
) = PendingSplit(
    invokerId = invokerId,
    groupId = groupId,
    amount = BigDecimal("90.00"),
    currency = "USD",
    description = "dinner",
    participantIds = participantIds,
    promptMessageId = 1,
    stage = SplitFlowStage.CHOOSING_MODE,
)

private fun CallbackFixture.anEnteringAmountsFlow(
    invokerId: MemberId,
    groupId: GroupId,
    participantIds: List<MemberId>,
) = PendingSplit(
    invokerId = invokerId,
    groupId = groupId,
    amount = BigDecimal("90.00"),
    currency = "USD",
    description = "dinner",
    participantIds = participantIds,
    promptMessageId = 1,
    stage = SplitFlowStage.ENTERING_AMOUNTS,
    actionsMessageId = 2,
)

private suspend fun CallbackFixture.setFlow(flow: PendingSplit) = splitStateStore.set(CALLBACK_CHAT_ID, flow)

private suspend fun CallbackFixture.currentFlow() =
    splitStateStore.listAll(CALLBACK_CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()

private suspend fun CallbackFixture.expenseCreatedWith(
    groupId: GroupId,
    vararg shares: Pair<MemberId, BigDecimal>,
) {
    val expense = expenseRepository.listActive(groupId).single()
    expense.shares.associate { it.memberId to it.shareAmount } shouldBe shares.toMap()
}

private suspend fun CallbackFixture.tapEqual(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) = handler.handle(
    CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_MODE_EQUAL_DATA),
)

private suspend fun CallbackFixture.tapExact(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) = handler.handle(
    CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_MODE_EXACT_DATA),
)

private suspend fun CallbackFixture.pick(
    participantIndex: Int,
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) = handler.handle(
    CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, splitPickData(participantIndex)),
)

private suspend fun CallbackFixture.tapConfirm(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) = handler.handle(
    CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_CONFIRM_DATA),
)

private suspend fun CallbackFixture.tapCancel(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) = handler.handle(
    CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_CANCEL_DATA),
)

class SplitFlowCallbackHandlerSpec :
    StringSpec({

        "choosing Equal creates an equal-split expense and clears the flow" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.aChoosingModeFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.tapEqual(aliceId, groupId, messageId = 1)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("45.00"), bobId to BigDecimal("45.00"))
                fixture.telegramApi.editedMessages.single().let { (chatId, messageId, _) ->
                    chatId shouldBe -100L
                    messageId shouldBe 1L
                }
                fixture.currentFlow() shouldBe null
            }
        }

        "choosing Exact switches the table message and sends an actions message" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.aChoosingModeFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.tapExact(aliceId, groupId, messageId = 1)

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.telegramApi.editedRichMessages.single().let { (chatId, messageId, _) ->
                    chatId shouldBe -100L
                    messageId shouldBe 1L
                }
                fixture.telegramApi.sentMessages
                    .single()
                    .first shouldBe -100L

                val flow = fixture.currentFlow()
                flow?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
                flow?.actionsMessageId shouldBe 1L
                flow?.amountsEntered shouldBe emptyMap()
            }
        }

        "picking a participant records them as pending on the flow" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.pick(participantIndex = 1, memberId = aliceId, groupId = groupId, messageId = 1)

                val flow = fixture.currentFlow()
                flow?.pendingParticipantId shouldBe bobId
                flow?.pendingPromptMessageId shouldBe 1L
                fixture.telegramApi.sentForceReplyPrompts.single() shouldBe
                    (-100L to "How much is @bob's share? Reply to this message with an amount.")
            }
        }

        "picking a different participant while holding your own pending slot deletes your stale prompt" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 99),
                )

                fixture.pick(participantIndex = 1, memberId = aliceId, groupId = groupId, messageId = 1)

                fixture.telegramApi.deletedMessages.single() shouldBe (CALLBACK_CHAT_ID to 99L)
                fixture.telegramApi.sentMessages shouldBe emptyList()
                fixture.currentFlow()?.pendingParticipantId shouldBe bobId
            }
        }

        "claiming your own row while another participant is pending keeps their prompt and warns them" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 99),
                )

                fixture.pick(participantIndex = 1, memberId = bobId, groupId = groupId, messageId = 1)

                val displacedNotice =
                    "@alice, someone else is entering their amount now — " +
                        "tap \"Enter your amount\" again when you're ready."
                fixture.telegramApi.deletedMessages shouldBe emptyList()
                fixture.telegramApi.sentMessages shouldBe listOf(CALLBACK_CHAT_ID to displacedNotice)
                fixture.currentFlow()?.pendingParticipantId shouldBe bobId
                fixture.currentFlow()?.pendingPromptMessageId shouldBe 2L
                fixture.telegramApi.sentForceReplyPrompts.single() shouldBe
                    (CALLBACK_CHAT_ID to "How much is @bob's share? Reply to this message with an amount.")
            }
        }

        "re-picking the participant who already holds the prompt deletes their stale prompt" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 99),
                )

                fixture.pick(participantIndex = 0, memberId = aliceId, groupId = groupId, messageId = 1)

                fixture.telegramApi.deletedMessages.single() shouldBe (CALLBACK_CHAT_ID to 99L)
                fixture.telegramApi.sentMessages shouldBe emptyList()
                fixture.currentFlow()?.pendingParticipantId shouldBe aliceId
            }
        }

        "confirm creates the exact-split expense once amounts are entered" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(amountsEntered = mapOf(aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00"))),
                )

                fixture.tapConfirm(aliceId, groupId, messageId = 2)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00"))
                fixture.currentFlow() shouldBe null
            }
        }

        "cancel clears the flow without creating an expense" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId)))

                fixture.tapCancel(aliceId, groupId, messageId = 2)

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentFlow() shouldBe null
            }
        }

        "cancel deletes an outstanding, still-unanswered prompt" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 99),
                )

                fixture.tapCancel(aliceId, groupId, messageId = 2)

                fixture.telegramApi.deletedMessages.single() shouldBe (-100L to 99L)
            }
        }

        "a participant can pick their own row to answer out of turn, before auto-advance reaches them" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow =
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 3)
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(1)))

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.pendingIsAutoAdvance shouldBe false
            }
        }

        "a participant picking someone else's row is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow =
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 3)
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(0)))

                fixture.currentFlow()!!.pendingParticipantId shouldBe aliceId
                fixture.telegramApi.answeredCallbacks
                    .last()
                    .second shouldBe
                    "Only the person who started this split, or that participant, can do that."
            }
        }

        "mode choice, cancel, and confirm still reject a non-invoker" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.aChoosingModeFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, SPLIT_MODE_EQUAL_DATA),
                )

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.telegramApi.answeredCallbacks
                    .last()
                    .second shouldBe
                    "Only the person who started this split can do that."
            }
        }

        "a participant can pick their own row again after auto-advance has moved past them, to correct it" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow =
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)).copy(
                        amountsEntered = mapOf(bobId to BigDecimal("40.00")),
                        pendingParticipantId = null,
                        pendingPromptMessageId = null,
                    )
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(1)))

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.amountsEntered shouldBe mapOf(bobId to BigDecimal("40.00"))
            }
        }

        "a callback from someone other than the invoker is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBob()
                fixture.setFlow(fixture.aChoosingModeFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.tapEqual(bobId, groupId, messageId = 1)

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentFlow()?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                fixture.telegramApi.answeredCallbacks
                    .single()
                    .third shouldBe true
            }
        }

        "a callback against a stale, superseded flow's message is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                fixture.setFlow(
                    fixture
                        .aChoosingModeFlow(aliceId, groupId, listOf(aliceId))
                        .copy(amount = BigDecimal("30.00"), description = "coffee", promptMessageId = 5),
                )

                fixture.tapEqual(aliceId, groupId, messageId = 1)

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentFlow()?.promptMessageId shouldBe 5
                fixture.telegramApi.answeredCallbacks
                    .single()
                    .third shouldBe true
            }
        }

        "a callback for a chat with no pending flow is answered but ignored" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.tapEqual(aliceId, groupId, messageId = 1)

                fixture.telegramApi.answeredCallbacks
                    .single()
                    .first shouldBe "cbq1"
                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
            }
        }

        "entering from the pending list sends a fresh prompt to that participant" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                fixture.telegramApi.sentForceReplyPrompts.isNotEmpty() shouldBe true
            }
        }

        "entering from the pending list to correct an already-submitted amount" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(amountsEntered = mapOf(bobId to BigDecimal("40.00"))),
                )

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.amountsEntered shouldBe mapOf(bobId to BigDecimal("40.00"))
            }
        }

        "entering from the pending list for a non-participant is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val carolId = fixture.resolver.resolveMember("3", "carol", "Carol")
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, carolId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                fixture.currentFlow()!!.pendingParticipantId shouldBe null
            }
        }

        "entering from the pending list for a flow already closed by someone else shows it's no longer active" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                // No flow set for prompt message id 1 — it was already confirmed/cancelled.

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                fixture.telegramApi.answeredCallbacks.last() shouldBe Triple("cbq", "This split is no longer active.", true)
            }
        }
    })
