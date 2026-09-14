package split.telegram.splitflow

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.MemberId
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.IdentityFixture
import split.telegram.ReplyContext
import split.telegram.carol
import split.telegram.personas
import split.telegram.withTestDatabase
import java.math.BigDecimal

private const val REPLY_CHAT_ID = -100L
private const val INVALID_AMOUNT_MESSAGE =
    "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50."

private class ReplyFixture(
    db: Database,
) : IdentityFixture(db) {
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val handler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)
}

/** Runs [block] — a scenario where someone replies with an amount mid-split — against a fresh fixture. */
private suspend fun duringReply(block: suspend ReplyFixture.() -> Unit) {
    withTestDatabase { db -> ReplyFixture(db).block() }
}

private fun ReplyFixture.anEnteringAmountsFlow(
    invokerId: MemberId,
    participantIds: List<MemberId>,
    pendingParticipantId: MemberId?,
    pendingPromptMessageId: Long? = 3,
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
    pendingParticipantId = pendingParticipantId,
    pendingPromptMessageId = pendingPromptMessageId,
)

private suspend fun ReplyFixture.setFlow(flow: PendingSplit) = splitStateStore.set(REPLY_CHAT_ID, flow)

private suspend fun ReplyFixture.currentFlow() = splitStateStore.listAll(REPLY_CHAT_ID).singleOrNull()

private suspend fun ReplyFixture.reply(
    memberId: MemberId,
    replyToMessageId: Long,
    text: String,
) = handler.handle(ReplyContext(REPLY_CHAT_ID, memberId, groupId, replyToMessageId, text))

private suspend fun ReplyFixture.replyIsRejectedAsInvalidAmount(
    memberId: MemberId,
    replyToMessageId: Long,
    text: String,
    pending: PendingSplit,
) {
    reply(memberId, replyToMessageId, text)

    currentFlow() shouldBe pending
    telegramApi.sentMessages.single().let { (_, message) -> message shouldBe INVALID_AMOUNT_MESSAGE }
}

class SplitFlowReplyHandlerSpec :
    DescribeSpec({

        describe("entering an amount advances the flow") {
            it("fills in the pending participant, edits both messages, and auto-advances to the next unfilled one") {
                duringReply {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice, group.bobby),
                            pendingParticipantId = group.bobby,
                        ),
                    )

                    reply(group.alice, replyToMessageId = 3, text = "40")

                    val flow = currentFlow() as? PendingSplit
                    flow?.amountsEntered shouldBe mapOf(group.bobby to BigDecimal("40.00"))
                    flow?.pendingParticipantId shouldBe group.alice
                    telegramApi.sentForceReplyPrompts.single().let { (_, text) ->
                        text shouldBe "How much is @alice's share? Reply to this message with an amount."
                    }
                    telegramApi.editedRichMessages.single().let { (chatId, messageId, _) ->
                        chatId shouldBe REPLY_CHAT_ID
                        messageId shouldBe 1L
                    }

                    telegramApi.deletedMessages.single() shouldBe (REPLY_CHAT_ID to 2L)
                    telegramApi.sentMessages.single().first shouldBe REPLY_CHAT_ID
                    flow?.actionsMessageId shouldBe 1L
                    flow?.pendingPromptMessageId shouldBe 2L
                }
            }

            it("does not auto-advance to the next participant when the reply was for a manually-picked one") {
                duringReply {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice, group.bobby),
                            pendingParticipantId = group.bobby,
                        ).copy(pendingIsAutoAdvance = false),
                    )

                    reply(group.alice, replyToMessageId = 3, text = "40")

                    val flow = currentFlow() as? PendingSplit
                    flow?.amountsEntered shouldBe mapOf(group.bobby to BigDecimal("40.00"))
                    flow?.pendingParticipantId shouldBe null
                    flow?.pendingPromptMessageId shouldBe null
                    telegramApi.sentForceReplyPrompts shouldBe emptyList()
                }
            }

            it("stops advancing and waits for Confirm once every participant has an amount") {
                duringReply {
                    val group = personas().alice().inGroup()
                    setFlow(anEnteringAmountsFlow(group.alice, listOf(group.alice), pendingParticipantId = group.alice))

                    reply(group.alice, replyToMessageId = 3, text = "90")

                    val flow = currentFlow() as? PendingSplit
                    flow?.amountsEntered shouldBe mapOf(group.alice to BigDecimal("90.00"))
                    flow?.pendingParticipantId shouldBe null
                    flow?.pendingPromptMessageId shouldBe null
                    telegramApi.sentForceReplyPrompts shouldBe emptyList()

                    telegramApi.deletedMessages.single() shouldBe (REPLY_CHAT_ID to 2L)
                    flow?.actionsMessageId shouldBe 1L
                }
            }
        }

        describe("validating the entered amount") {
            it("an invalid amount reply doesn't touch state and asks again") {
                duringReply {
                    val group = personas().alice().known()
                    val pending =
                        anEnteringAmountsFlow(group.alice, listOf(group.alice), pendingParticipantId = group.alice)
                    setFlow(pending)

                    replyIsRejectedAsInvalidAmount(group.alice, replyToMessageId = 3, text = "not a number", pending)
                }
            }

            it("a reply with more than 2 decimal places doesn't touch state and asks again") {
                duringReply {
                    val group = personas().alice().known()
                    val pending =
                        anEnteringAmountsFlow(group.alice, listOf(group.alice), pendingParticipantId = group.alice)
                    setFlow(pending)

                    replyIsRejectedAsInvalidAmount(group.alice, replyToMessageId = 3, text = "33.333", pending)
                }
            }

            it("a non-positive amount reply doesn't touch state and asks again") {
                duringReply {
                    val group = personas().alice().known()
                    val pending =
                        anEnteringAmountsFlow(group.alice, listOf(group.alice), pendingParticipantId = group.alice)
                    setFlow(pending)

                    replyIsRejectedAsInvalidAmount(group.alice, replyToMessageId = 3, text = "-5", pending)
                }
            }
        }

        describe("who may answer") {
            it("ignores a reply from someone other than the invoker") {
                duringReply {
                    val group = personas().alice().bobby().inGroup()
                    val pending =
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice, group.bobby),
                            pendingParticipantId = group.alice,
                        )
                    setFlow(pending)

                    reply(group.bobby, replyToMessageId = 3, text = "40")

                    currentFlow() shouldBe pending
                    telegramApi.sentMessages shouldBe emptyList()
                }
            }

            it("lets a named participant who isn't the invoker answer their own auto-advanced prompt") {
                duringReply {
                    val group = personas().alice().bobby().inGroup()
                    setFlow(
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice, group.bobby),
                            pendingParticipantId = group.bobby,
                        ),
                    )

                    reply(group.bobby, replyToMessageId = 3, text = "45.00")

                    (currentFlow() as PendingSplit).amountsEntered shouldBe mapOf(group.bobby to BigDecimal("45.00"))
                }
            }

            it("ignores a reply from someone who is neither the invoker nor the pending participant") {
                duringReply {
                    val cast = personas().alice().bobby().carol()
                    val group = cast.inGroup()
                    setFlow(
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice, group.bobby, group.carol),
                            pendingParticipantId = group.bobby,
                        ),
                    )

                    reply(group.carol, replyToMessageId = 3, text = "45.00")

                    (currentFlow() as PendingSplit).amountsEntered shouldBe emptyMap()
                }
            }
        }

        describe("targeting the right prompt") {
            it("ignores a reply to the wrong message") {
                duringReply {
                    val group = personas().alice().known()
                    val pending =
                        anEnteringAmountsFlow(group.alice, listOf(group.alice), pendingParticipantId = group.alice)
                    setFlow(pending)

                    reply(group.alice, replyToMessageId = 999, text = "40")

                    currentFlow() shouldBe pending
                }
            }

            it("ignores a reply when no participant is pending") {
                duringReply {
                    val group = personas().alice().known()
                    val pending =
                        anEnteringAmountsFlow(
                            group.alice,
                            listOf(group.alice),
                            pendingParticipantId = null,
                            pendingPromptMessageId = null,
                        )
                    setFlow(pending)

                    reply(group.alice, replyToMessageId = 1, text = "40")

                    currentFlow() shouldBe pending
                }
            }
        }
    })
