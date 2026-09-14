package split.telegram.splitflow

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedExpenseRepository
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.CallbackContext
import split.telegram.IdentityFixture
import split.telegram.carol
import split.telegram.chat
import split.telegram.personas
import split.telegram.withTestDatabase
import java.math.BigDecimal

private const val CALLBACK_CHAT_ID = -100L

/** Every flow in this file starts life showing its mode-choice/amounts prompt on this message. */
private const val FLOW_PROMPT_MESSAGE_ID = 1L

/** The message a "pending list" entry lives on — SplitFlowCallbackHandler ignores it, so any id will do. */
private const val PENDING_LIST_MESSAGE_ID = 999L

private class CallbackFixture(
    db: Database,
) : IdentityFixture(db) {
    val expenseRepository = ExposedExpenseRepository(db)
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val handler =
        SplitFlowCallbackHandler(splitStateStore, memberRepository, expenseRepository, platformDirectory, telegramApi)
}

/** Runs [block] — a scenario where someone taps a button mid-split — against a fresh [CallbackFixture]. */
private suspend fun duringSplitFlow(block: suspend CallbackFixture.() -> Unit) {
    withTestDatabase { db -> CallbackFixture(db).block() }
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
    promptMessageId = FLOW_PROMPT_MESSAGE_ID,
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
    promptMessageId = FLOW_PROMPT_MESSAGE_ID,
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

/**
 * callbackQueryId is Telegram's opaque per-tap correlation id — irrelevant except where a test checks
 * it's echoed back.
 */
private suspend fun CallbackFixture.tapEqual(
    memberId: MemberId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) {
    telegramApi.userTapped(nameOf(memberId), messageId, SPLIT_MODE_EQUAL_DATA)
    handler.handle(
        CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_MODE_EQUAL_DATA),
    )
}

private suspend fun CallbackFixture.tapExact(
    memberId: MemberId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) {
    telegramApi.userTapped(nameOf(memberId), messageId, SPLIT_MODE_EXACT_DATA)
    handler.handle(
        CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_MODE_EXACT_DATA),
    )
}

/** Taps the row for [participant] — the position on the keyboard is looked up, never hard-coded. */
private suspend fun CallbackFixture.pick(
    participant: MemberId,
    tappedBy: MemberId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) {
    val index = currentFlow()!!.participantIds.indexOf(participant)
    val data = splitPickData(index)
    telegramApi.userTapped(nameOf(tappedBy), messageId, data)
    handler.handle(CallbackContext(CALLBACK_CHAT_ID, tappedBy, groupId, callbackQueryId, messageId, data))
}

private suspend fun CallbackFixture.tapConfirm(
    memberId: MemberId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) {
    telegramApi.userTapped(nameOf(memberId), messageId, SPLIT_CONFIRM_DATA)
    handler.handle(CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_CONFIRM_DATA))
}

/** Taps a "pending list" entry to enter/correct [tappedBy]'s own amount on the flow prompted at [promptMessageId]. */
private suspend fun CallbackFixture.enterFromPendingList(
    tappedBy: MemberId,
    promptMessageId: Long = FLOW_PROMPT_MESSAGE_ID,
) {
    val data = pendingSplitEnterData(promptMessageId)
    telegramApi.userTapped(nameOf(tappedBy), PENDING_LIST_MESSAGE_ID, data)
    handler.handle(CallbackContext(CALLBACK_CHAT_ID, tappedBy, groupId, "cbq", PENDING_LIST_MESSAGE_ID, data))
}

private suspend fun CallbackFixture.tapCancel(
    memberId: MemberId,
    messageId: Long,
    callbackQueryId: String = "cbq1",
) {
    telegramApi.userTapped(nameOf(memberId), messageId, SPLIT_CANCEL_DATA)
    handler.handle(CallbackContext(CALLBACK_CHAT_ID, memberId, groupId, callbackQueryId, messageId, SPLIT_CANCEL_DATA))
}

class SplitFlowCallbackHandlerSpec :
    DescribeSpec({

        describe("a tap against a flow that can no longer be found") {
            it("is answered but ignored when the chat has no pending flow") {
                duringSplitFlow {
                    val group = personas().alice().known()

                    tapEqual(group.alice, messageId = 1)

                    chat() shouldBe
                        """
                        [alice] taps [split:mode:equal] on #1
                        (alert to alice: "This split is no longer active.")
                        """.trimIndent()
                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                }
            }

            it("is rejected when it targets a stale, superseded flow message") {
                duringSplitFlow {
                    val group = personas().alice().known()
                    val stalePromptMessageId = 5L
                    setFlow(
                        aChoosingModeFlow(group.alice, group.groupId, listOf(group.alice))
                            .copy(
                                amount = BigDecimal("30.00"),
                                description = "coffee",
                                promptMessageId = stalePromptMessageId,
                            ),
                    )

                    tapEqual(group.alice, messageId = 1)

                    chat() shouldBe
                        """
                        [alice] taps [split:mode:equal] on #1
                        (alert to alice: "This split is no longer active.")
                        """.trimIndent()
                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    currentFlow()?.promptMessageId shouldBe stalePromptMessageId
                }
            }
        }

        describe("choosing a split mode") {
            it("creates an equal-split expense and clears the flow when Equal is chosen") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(aChoosingModeFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    tapEqual(group.alice, messageId = 1)

                    expenseCreatedWith(
                        group.groupId,
                        group.alice to BigDecimal("45.00"),
                        group.bobby to BigDecimal("45.00"),
                    )
                    telegramApi.editedMessages.single().let { (chatId, messageId, _) ->
                        chatId shouldBe CALLBACK_CHAT_ID
                        messageId shouldBe FLOW_PROMPT_MESSAGE_ID
                    }
                    currentFlow() shouldBe null
                }
            }

            it("switches to entering amounts and sends an actions message when Exact is chosen") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(aChoosingModeFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    tapExact(group.alice, messageId = 1)

                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    telegramApi.editedRichMessages.single().let { (chatId, messageId, _) ->
                        chatId shouldBe CALLBACK_CHAT_ID
                        messageId shouldBe FLOW_PROMPT_MESSAGE_ID
                    }
                    telegramApi.sentMessages.single().first shouldBe CALLBACK_CHAT_ID

                    val flow = currentFlow()
                    flow?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
                    flow?.actionsMessageId shouldBe 1L
                    flow?.amountsEntered shouldBe emptyMap()
                }
            }

            it("rejects a mode choice from anyone other than the person who started the split") {
                duringSplitFlow {
                    val group = personas().alice().bobby().known()
                    setFlow(aChoosingModeFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    tapEqual(group.bobby, messageId = 1)

                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    currentFlow()?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                    telegramApi.answeredCallbacks.single().let { (_, message, showAlert) ->
                        message shouldBe "Only the person who started this split can do that."
                        showAlert shouldBe true
                    }
                }
            }
        }

        describe("assigning who enters an amount next") {
            it("records the picked participant as pending and prompts them") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    pick(participant = group.bobby, tappedBy = group.alice, messageId = 1)

                    val flow = currentFlow()
                    flow?.pendingParticipantId shouldBe group.bobby
                    flow?.pendingPromptMessageId shouldBe 1L
                    telegramApi.sentForceReplyPrompts.single() shouldBe
                        (-100L to "How much is @bobby's share? Reply to this message with an amount.")
                }
            }

            it("deletes your own stale prompt when you pick a different participant while holding the pending slot") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 99),
                    )

                    pick(participant = group.bobby, tappedBy = group.alice, messageId = 1)

                    telegramApi.deletedMessages.single() shouldBe (CALLBACK_CHAT_ID to 99L)
                    telegramApi.sentMessages shouldBe emptyList()
                    currentFlow()?.pendingParticipantId shouldBe group.bobby
                }
            }

            it("keeps another participant's prompt and warns them when you claim your own row instead") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 99),
                    )

                    pick(participant = group.bobby, tappedBy = group.bobby, messageId = 1)

                    val displacedNotice =
                        "@alice, someone else is entering their amount now — " +
                            "tap \"Enter your amount\" again when you're ready."
                    telegramApi.deletedMessages shouldBe emptyList()
                    telegramApi.sentMessages shouldBe listOf(CALLBACK_CHAT_ID to displacedNotice)
                    currentFlow()?.pendingParticipantId shouldBe group.bobby
                    currentFlow()?.pendingPromptMessageId shouldBe 2L
                    telegramApi.sentForceReplyPrompts.single() shouldBe
                        (CALLBACK_CHAT_ID to "How much is @bobby's share? Reply to this message with an amount.")
                }
            }

            it("deletes the stale prompt when re-picking the participant who already holds it") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 99),
                    )

                    pick(participant = group.alice, tappedBy = group.alice, messageId = 1)

                    telegramApi.deletedMessages.single() shouldBe (CALLBACK_CHAT_ID to 99L)
                    telegramApi.sentMessages shouldBe emptyList()
                    currentFlow()?.pendingParticipantId shouldBe group.alice
                }
            }

            it("lets a participant pick their own row out of turn, before auto-advance reaches them") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    val flow =
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 3)
                    setFlow(flow)

                    pick(participant = group.bobby, tappedBy = group.bobby, messageId = 1)

                    val updated = currentFlow()!!
                    updated.pendingParticipantId shouldBe group.bobby
                    updated.pendingIsAutoAdvance shouldBe false
                }
            }

            it("lets a participant pick their own row again after auto-advance has moved past them, to correct it") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    val flow =
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)).copy(
                            amountsEntered = mapOf(group.bobby to BigDecimal("40.00")),
                            pendingParticipantId = null,
                            pendingPromptMessageId = null,
                        )
                    setFlow(flow)

                    pick(participant = group.bobby, tappedBy = group.bobby, messageId = 1)

                    val updated = currentFlow()!!
                    updated.pendingParticipantId shouldBe group.bobby
                    updated.amountsEntered shouldBe mapOf(group.bobby to BigDecimal("40.00"))
                }
            }

            it("rejects a participant picking someone else's row") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    val flow =
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 3)
                    setFlow(flow)

                    pick(participant = group.alice, tappedBy = group.bobby, messageId = 1)

                    currentFlow()!!.pendingParticipantId shouldBe group.alice
                    chat() shouldBe
                        """
                        [bobby] taps [split:pick:0] on #1
                        (alert to bobby: "Only the person who started this split, or that participant, can do that.")
                        """.trimIndent()
                }
            }
        }

        describe("confirming or cancelling the split") {
            it("creates the exact-split expense once amounts are entered and confirmed") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(
                                amountsEntered =
                                    mapOf(group.alice to BigDecimal("50.00"), group.bobby to BigDecimal("40.00")),
                            ),
                    )

                    tapConfirm(group.alice, messageId = 2)

                    expenseCreatedWith(
                        group.groupId,
                        group.alice to BigDecimal("50.00"),
                        group.bobby to BigDecimal("40.00"),
                    )
                    currentFlow() shouldBe null
                }
            }

            it("clears the flow without creating an expense when cancelled") {
                duringSplitFlow {
                    val group = personas().alice().known()
                    setFlow(anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice)))

                    tapCancel(group.alice, messageId = 2)

                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    currentFlow() shouldBe null
                }
            }

            it("deletes an outstanding, still-unanswered prompt when cancelled") {
                duringSplitFlow {
                    val group = personas().alice().known()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice))
                            .copy(pendingParticipantId = group.alice, pendingPromptMessageId = 99),
                    )

                    tapCancel(group.alice, messageId = 2)

                    telegramApi.deletedMessages.single() shouldBe (CALLBACK_CHAT_ID to 99L)
                }
            }
        }

        describe("entering an amount from the pending list") {
            it("sends a fresh prompt to the participant who taps their row") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    enterFromPendingList(tappedBy = group.bobby)

                    val updated = currentFlow()!!
                    updated.pendingParticipantId shouldBe group.bobby
                    telegramApi.sentForceReplyPrompts.single() shouldBe
                        (CALLBACK_CHAT_ID to "How much is @bobby's share? Reply to this message with an amount.")
                }
            }

            it("lets a participant correct an amount they already submitted") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby))
                            .copy(amountsEntered = mapOf(group.bobby to BigDecimal("40.00"))),
                    )

                    enterFromPendingList(tappedBy = group.bobby)

                    val updated = currentFlow()!!
                    updated.pendingParticipantId shouldBe group.bobby
                    updated.amountsEntered shouldBe mapOf(group.bobby to BigDecimal("40.00"))
                }
            }

            it("rejects a tap from someone who isn't a participant in the split") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    val carolId = carol()
                    setFlow(anEnteringAmountsFlow(group.alice, group.groupId, listOf(group.alice, group.bobby)))

                    enterFromPendingList(tappedBy = carolId)

                    currentFlow()!!.pendingParticipantId shouldBe null
                    chat() shouldBe
                        """
                        [carol] taps [pending:enter:1] on #999
                        (alert to carol: "You're not part of this split.")
                        """.trimIndent()
                }
            }

            it("shows the split is no longer active when it was already closed by someone else") {
                duringSplitFlow {
                    val group = personas().alice().bobby().inGroup()
                    // No flow set for prompt message id 1 — it was already confirmed/cancelled.

                    enterFromPendingList(tappedBy = group.bobby)

                    chat() shouldBe
                        """
                        [bobby] taps [pending:enter:1] on #999
                        (alert to bobby: "This split is no longer active.")
                        """.trimIndent()
                }
            }
        }
    })
