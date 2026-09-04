package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import java.math.BigDecimal

private const val REPLY_CHAT_ID = -100L
private const val INVALID_AMOUNT_MESSAGE =
    "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50."

private class ReplyFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val memberRepository = ExposedMemberRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
    val telegramApi = FakeTelegramApi()
    val splitStateStore = SplitStateStore()
    val handler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)
}

private suspend fun ReplyFixture.aliceAndBob(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val bobId = resolver.resolveMember("2", "bob", "Bob")
    val groupId = resolver.resolveGroup("-100")
    return Triple(aliceId, bobId, groupId)
}

private suspend fun ReplyFixture.aliceAndBobInGroup(): Triple<MemberId, MemberId, GroupId> {
    val (aliceId, bobId, groupId) = aliceAndBob()
    resolver.ensureGroupMembership(groupId, aliceId)
    resolver.ensureGroupMembership(groupId, bobId)
    return Triple(aliceId, bobId, groupId)
}

private suspend fun ReplyFixture.aliceOnly(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100")
    return aliceId to groupId
}

private suspend fun ReplyFixture.aliceOnlyInGroup(): Pair<MemberId, GroupId> {
    val (aliceId, groupId) = aliceOnly()
    resolver.ensureGroupMembership(groupId, aliceId)
    return aliceId to groupId
}

private fun ReplyFixture.anEnteringAmountsFlow(
    invokerId: MemberId,
    groupId: GroupId,
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

private fun ReplyFixture.setFlow(flow: PendingSplit) = splitStateStore.set(REPLY_CHAT_ID, flow)

private fun ReplyFixture.currentFlow() = splitStateStore.get(REPLY_CHAT_ID)

private suspend fun ReplyFixture.reply(
    memberId: MemberId,
    groupId: GroupId,
    replyToMessageId: Long,
    text: String,
) = handler.handle(ReplyContext(REPLY_CHAT_ID, memberId, groupId, replyToMessageId, text))

private suspend fun ReplyFixture.replyIsRejectedAsInvalidAmount(
    memberId: MemberId,
    groupId: GroupId,
    replyToMessageId: Long,
    text: String,
    pending: PendingSplit,
) {
    reply(memberId, groupId, replyToMessageId, text)

    currentFlow() shouldBe pending
    telegramApi.sentMessages.single().second shouldBe INVALID_AMOUNT_MESSAGE
}

class SplitFlowReplyHandlerSpec :
    StringSpec({

        "a valid amount reply fills in the pending participant, edits both messages, and auto-advances to the next unfilled one" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture.anEnteringAmountsFlow(
                        aliceId,
                        groupId,
                        listOf(aliceId, bobId),
                        pendingParticipantId = bobId,
                    ),
                )

                fixture.reply(aliceId, groupId, replyToMessageId = 3, text = "40")

                val flow = fixture.currentFlow() as? PendingSplit
                flow?.amountsEntered shouldBe mapOf(bobId to BigDecimal("40"))
                flow?.pendingParticipantId shouldBe aliceId
                fixture.telegramApi.sentForceReplyPrompts
                    .single()
                    .second shouldBe
                    "How much is @alice's share? Reply to this message with an amount."
                fixture.telegramApi.editedRichMessages.single().let { (chatId, messageId, _) ->
                    chatId shouldBe -100L
                    messageId shouldBe 1L
                }

                fixture.telegramApi.deletedMessages.single() shouldBe (-100L to 2L)
                fixture.telegramApi.sentMessages
                    .single()
                    .first shouldBe -100L
                flow?.actionsMessageId shouldBe 1L
                flow?.pendingPromptMessageId shouldBe 2L
            }
        }

        "a reply for a manually-picked participant does not auto-advance to the next one" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture
                        .anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId), pendingParticipantId = bobId)
                        .copy(pendingIsAutoAdvance = false),
                )

                fixture.reply(aliceId, groupId, replyToMessageId = 3, text = "40")

                val flow = fixture.currentFlow() as? PendingSplit
                flow?.amountsEntered shouldBe mapOf(bobId to BigDecimal("40"))
                flow?.pendingParticipantId shouldBe null
                flow?.pendingPromptMessageId shouldBe null
                fixture.telegramApi.sentForceReplyPrompts shouldBe emptyList()
            }
        }

        "once every participant has an amount, the flow stops advancing and waits for Confirm" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnlyInGroup()
                fixture.setFlow(
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId), pendingParticipantId = aliceId),
                )

                fixture.reply(aliceId, groupId, replyToMessageId = 3, text = "90")

                val flow = fixture.currentFlow() as? PendingSplit
                flow?.amountsEntered shouldBe mapOf(aliceId to BigDecimal("90"))
                flow?.pendingParticipantId shouldBe null
                flow?.pendingPromptMessageId shouldBe null
                fixture.telegramApi.sentForceReplyPrompts shouldBe emptyList()

                fixture.telegramApi.deletedMessages.single() shouldBe (-100L to 2L)
                flow?.actionsMessageId shouldBe 1L
            }
        }

        "an invalid amount reply doesn't touch state and asks again" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                val pending =
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId), pendingParticipantId = aliceId)
                fixture.setFlow(pending)

                fixture.replyIsRejectedAsInvalidAmount(
                    aliceId,
                    groupId,
                    replyToMessageId = 3,
                    text = "not a number",
                    pending,
                )
            }
        }

        "a reply with more than 2 decimal places doesn't touch state and asks again" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                val pending =
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId), pendingParticipantId = aliceId)
                fixture.setFlow(pending)

                fixture.replyIsRejectedAsInvalidAmount(aliceId, groupId, replyToMessageId = 3, text = "33.333", pending)
            }
        }

        "a non-positive amount reply doesn't touch state and asks again" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                val pending =
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId), pendingParticipantId = aliceId)
                fixture.setFlow(pending)

                fixture.replyIsRejectedAsInvalidAmount(aliceId, groupId, replyToMessageId = 3, text = "-5", pending)
            }
        }

        "a reply from someone other than the invoker is ignored" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBob()
                val pending =
                    fixture.anEnteringAmountsFlow(
                        aliceId,
                        groupId,
                        listOf(aliceId, bobId),
                        pendingParticipantId = bobId,
                    )
                fixture.setFlow(pending)

                fixture.reply(bobId, groupId, replyToMessageId = 3, text = "40")

                fixture.currentFlow() shouldBe pending
                fixture.telegramApi.sentMessages shouldBe emptyList()
            }
        }

        "a reply to the wrong message is ignored" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                val pending =
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId), pendingParticipantId = aliceId)
                fixture.setFlow(pending)

                fixture.reply(aliceId, groupId, replyToMessageId = 999, text = "40")

                fixture.currentFlow() shouldBe pending
            }
        }

        "a reply when no participant is pending is ignored" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()
                val pending =
                    fixture.anEnteringAmountsFlow(
                        aliceId,
                        groupId,
                        listOf(aliceId),
                        pendingParticipantId = null,
                        pendingPromptMessageId = null,
                    )
                fixture.setFlow(pending)

                fixture.reply(aliceId, groupId, replyToMessageId = 1, text = "40")

                fixture.currentFlow() shouldBe pending
            }
        }
    })
