package split.telegram.splitflow

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.MemberId
import split.storage.ExposedExpenseRepository
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.CommandContext
import split.telegram.GroupOf
import split.telegram.IdentityFixture
import split.telegram.ReplyContext
import split.telegram.commands.SplitExpenseCommand
import split.telegram.joined
import split.telegram.personas
import split.telegram.withTestDatabase
import java.math.BigDecimal

private const val DRAFT_CHAT_ID = -100L

private class DraftFixture(
    db: Database,
) : IdentityFixture(db) {
    val expenseRepository = ExposedExpenseRepository(db)
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val flowStarter =
        SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
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
    val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)
}

/** Runs [block] — a scenario where someone answers the bot's step-by-step /split prompts — against a fresh fixture. */
private suspend fun duringDraftReply(block: suspend DraftFixture.() -> Unit) {
    withTestDatabase { db -> DraftFixture(db).block() }
}

/** Alice joined to the group; Bobby known (the tests mention him by text) but never added as a member. */
private suspend fun DraftFixture.aliceInGroupWithBobbyKnown(): GroupOf {
    val group = personas().alice().bobby().known()
    joined(group.groupId, listOf(group.alice))
    return group
}

private suspend fun DraftFixture.startSplit(
    memberId: MemberId,
    args: String,
) = splitCommand.handle(CommandContext(DRAFT_CHAT_ID, memberId, "1", groupId, args))

private suspend fun DraftFixture.states() = splitStateStore.listAll(DRAFT_CHAT_ID)

private suspend fun DraftFixture.currentDraft() = states().filterIsInstance<PendingSplitDraft>().singleOrNull()

private suspend fun DraftFixture.currentFlow() = states().filterIsInstance<PendingSplit>().singleOrNull()

private suspend fun DraftFixture.answer(
    memberId: MemberId,
    replyToMessageId: Long,
    text: String,
) = handler.handle(ReplyContext(DRAFT_CHAT_ID, memberId, groupId, replyToMessageId, text))

private suspend fun DraftFixture.answerAsInvoker(
    memberId: MemberId,
    text: String,
) {
    val promptId = currentDraft()!!.promptMessageId
    answer(memberId, promptId, text)
}

class SplitDraftReplyHandlerSpec :
    DescribeSpec({

        describe("advancing the draft one field at a time") {
            it("completing description, amount, then participants starts the mode-choice flow") {
                duringDraftReply {
                    val group = aliceInGroupWithBobbyKnown()

                    startSplit(group.alice, "")
                    answerAsInvoker(group.alice, "dinner")

                    currentDraft()?.awaiting shouldBe SplitDraftField.AMOUNT
                    answerAsInvoker(group.alice, "90")

                    currentDraft()?.awaiting shouldBe SplitDraftField.PARTICIPANTS
                    answerAsInvoker(group.alice, "@bobby")

                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    currentFlow()?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                    currentFlow()?.participantIds shouldBe listOf(group.alice, group.bobby)
                }
            }

            it("an empty description reply doesn't advance the draft") {
                duringDraftReply {
                    val group = personas().alice().inGroup()

                    startSplit(group.alice, "")
                    answerAsInvoker(group.alice, "   ")

                    currentDraft()?.awaiting shouldBe SplitDraftField.DESCRIPTION
                    currentDraft()?.description shouldBe null
                }
            }

            it("an invalid amount reply doesn't advance the draft") {
                duringDraftReply {
                    val group = personas().alice().inGroup()

                    startSplit(group.alice, "dinner")
                    answerAsInvoker(group.alice, "not a number")

                    currentDraft()?.awaiting shouldBe SplitDraftField.AMOUNT
                    currentDraft()?.amount shouldBe null
                }
            }

            it("an amount reply can override the currency") {
                duringDraftReply {
                    val group = personas().alice().inGroup()

                    startSplit(group.alice, "dinner")
                    answerAsInvoker(group.alice, "90 EUR")

                    currentDraft()?.amount shouldBe BigDecimal("90.00")
                    currentDraft()?.currency shouldBe "EUR"
                    currentDraft()?.awaiting shouldBe SplitDraftField.PARTICIPANTS
                }
            }
        }

        describe("finishing the draft") {
            it("an equal keyword carried through the draft finishes without a mode-choice tap") {
                duringDraftReply {
                    val group = aliceInGroupWithBobbyKnown()

                    startSplit(group.alice, "equal @bobby")
                    answerAsInvoker(group.alice, "dinner")
                    answerAsInvoker(group.alice, "90")

                    currentDraft() shouldBe null
                    val expense = expenseRepository.listActive(group.groupId).single()
                    expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                        mapOf(
                            group.alice to BigDecimal("45.00"),
                            group.bobby to BigDecimal("45.00"),
                        )
                }
            }

            it("an unrecognized mention at the participants step errors and clears the draft") {
                duringDraftReply {
                    val group = personas().alice().inGroup()

                    startSplit(group.alice, "90 dinner")
                    answerAsInvoker(group.alice, "@stranger")

                    expenseRepository.listActive(group.groupId) shouldBe emptyList()
                    telegramApi.sentMessages.last().let { (_, text) ->
                        text shouldBe "I don't recognize <code>@stranger</code> yet — ask them to run /start with me first."
                    }
                }
            }
        }

        describe("authorization") {
            it("a reply from someone other than the invoker is ignored") {
                duringDraftReply {
                    val group = personas().alice().bobby().inGroup()

                    startSplit(group.alice, "")
                    val draftBefore = currentDraft()!!

                    answer(group.bobby, draftBefore.promptMessageId, "dinner")

                    currentDraft() shouldBe draftBefore
                }
            }
        }
    })
