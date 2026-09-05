package split.telegram

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
import java.math.BigDecimal

private const val CHAT_ID = -100L

private class FlowFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()
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
    val callbackHandler =
        SplitFlowCallbackHandler(splitStateStore, memberRepository, expenseRepository, platformDirectory, telegramApi)
    val replyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)
}

private suspend fun FlowFixture.aliceAndBobbyInGroup(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100")
    resolver.ensureGroupMembership(groupId, aliceId)
    val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
    return Triple(aliceId, bobbyId, groupId)
}

private suspend fun FlowFixture.startSplit(
    memberId: MemberId,
    groupId: GroupId,
    args: String,
) = splitCommand.handle(CommandContext(CHAT_ID, memberId, "1", groupId, args))

private suspend fun FlowFixture.currentFlow() =
    splitStateStore.listAll(CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()

private suspend fun FlowFixture.tapEqual(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
) = callbackHandler.handle(CallbackContext(CHAT_ID, memberId, groupId, "cbq", messageId, SPLIT_MODE_EQUAL_DATA))

private suspend fun FlowFixture.tapExact(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
) = callbackHandler.handle(CallbackContext(CHAT_ID, memberId, groupId, "cbq", messageId, SPLIT_MODE_EXACT_DATA))

private suspend fun FlowFixture.pick(
    participantIndex: Int,
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
) = callbackHandler.handle(
    CallbackContext(CHAT_ID, memberId, groupId, "cbq", messageId, splitPickData(participantIndex)),
)

private suspend fun FlowFixture.tapConfirm(
    memberId: MemberId,
    groupId: GroupId,
    messageId: Long,
) = callbackHandler.handle(CallbackContext(CHAT_ID, memberId, groupId, "cbq", messageId, SPLIT_CONFIRM_DATA))

private suspend fun FlowFixture.replyWithAmount(
    memberId: MemberId,
    groupId: GroupId,
    replyToMessageId: Long,
    amount: String,
) = replyHandler.handle(ReplyContext(CHAT_ID, memberId, groupId, replyToMessageId, amount))

private suspend fun FlowFixture.expenseCreatedWith(
    groupId: GroupId,
    vararg shares: Pair<MemberId, BigDecimal>,
) {
    val expense = expenseRepository.listActive(groupId).single()
    expense.shares.associate { it.memberId to it.shareAmount } shouldBe shares.toMap()
}

class SplitFlowSpec :
    StringSpec({

        "the full equal-split flow: choose Equal, expense created immediately" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val promptMessageId = fixture.currentFlow()!!.promptMessageId

                fixture.tapEqual(aliceId, groupId, promptMessageId)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("45.00"), bobbyId to BigDecimal("45.00"))
                fixture.currentFlow() shouldBe null
            }
        }

        "the full exact-split flow: choose Exact, enter both amounts, confirm" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val promptMessageId = fixture.currentFlow()!!.promptMessageId
                fixture.tapExact(aliceId, groupId, promptMessageId)

                fixture.pick(participantIndex = 0, memberId = aliceId, groupId = groupId, messageId = promptMessageId)
                val alicePromptId = fixture.currentFlow()!!.pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, alicePromptId, "50")

                fixture.currentFlow()?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()

                fixture.pick(participantIndex = 1, memberId = aliceId, groupId = groupId, messageId = promptMessageId)
                val bobPromptId = fixture.currentFlow()!!.pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, bobPromptId, "40")

                val latestActionsMessageId = fixture.currentFlow()!!.actionsMessageId!!
                fixture.tapConfirm(aliceId, groupId, latestActionsMessageId)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00"))
                fixture.currentFlow() shouldBe null
            }
        }

        "choosing Exact auto-advances through participants with no manual taps needed" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val promptMessageId = fixture.currentFlow()!!.promptMessageId
                fixture.tapExact(aliceId, groupId, promptMessageId)
                fixture.currentFlow()?.pendingParticipantId shouldBe aliceId
                var promptId = fixture.currentFlow()!!.pendingPromptMessageId!!

                fixture.replyWithAmount(aliceId, groupId, promptId, "50")
                fixture.currentFlow()?.pendingParticipantId shouldBe bobId
                promptId = fixture.currentFlow()!!.pendingPromptMessageId!!

                fixture.replyWithAmount(aliceId, groupId, promptId, "40")
                fixture.currentFlow()?.pendingParticipantId shouldBe null
                fixture.currentFlow()?.pendingPromptMessageId shouldBe null

                val latestActionsMessageId = fixture.currentFlow()!!.actionsMessageId!!
                fixture.tapConfirm(aliceId, groupId, latestActionsMessageId)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00"))
            }
        }

        "re-entering a participant's amount before confirming overwrites the earlier value" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val promptMessageId = fixture.currentFlow()!!.promptMessageId
                fixture.tapExact(aliceId, groupId, promptMessageId)

                fixture.pick(participantIndex = 0, memberId = aliceId, groupId = groupId, messageId = promptMessageId)
                val aliceFirstPromptId = fixture.currentFlow()!!.pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, aliceFirstPromptId, "50")
                fixture.pick(participantIndex = 1, memberId = aliceId, groupId = groupId, messageId = promptMessageId)
                val bobPromptId = fixture.currentFlow()!!.pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, bobPromptId, "40")
                val participantIds = listOf(aliceId, bobId)
                var amountsEntered = fixture.currentFlow()!!.amountsEntered
                splitIsReadyToConfirm(participantIds, amountsEntered, BigDecimal("90.00")) shouldBe true

                fixture.pick(participantIndex = 0, memberId = aliceId, groupId = groupId, messageId = promptMessageId)
                val aliceSecondPromptId = fixture.currentFlow()!!.pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, aliceSecondPromptId, "60")

                amountsEntered = fixture.currentFlow()!!.amountsEntered
                amountsEntered shouldBe mapOf(aliceId to BigDecimal("60.00"), bobId to BigDecimal("40.00"))
                splitIsReadyToConfirm(participantIds, amountsEntered, BigDecimal("90.00")) shouldBe false
            }
        }

        "a second /split coexists with the first, still-pending flow" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "30 coffee @bobby")
                val coffeePromptMessageId =
                    (fixture.splitStateStore.listAll(CHAT_ID).single() as PendingSplit).promptMessageId

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val dinnerFlow =
                    fixture.splitStateStore
                        .listAll(CHAT_ID)
                        .filterIsInstance<PendingSplit>()
                        .single { it.promptMessageId != coffeePromptMessageId }
                dinnerFlow.description shouldBe "dinner"

                fixture.tapEqual(aliceId, groupId, coffeePromptMessageId)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("15.00"), bobbyId to BigDecimal("15.00"))
                (fixture.splitStateStore.find(CHAT_ID, dinnerFlow.promptMessageId) as PendingSplit)
                    .description shouldBe "dinner"
            }
        }

        "cancelling one of two concurrent flows leaves the other's amounts untouched" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "30 coffee @bobby")
                val coffeePromptMessageId =
                    (fixture.splitStateStore.listAll(CHAT_ID).single() as PendingSplit).promptMessageId
                fixture.tapExact(aliceId, groupId, coffeePromptMessageId)
                fixture.pick(
                    participantIndex = 0,
                    memberId = aliceId,
                    groupId = groupId,
                    messageId = coffeePromptMessageId,
                )
                val coffeeAlicePromptId =
                    (fixture.splitStateStore.find(CHAT_ID, coffeePromptMessageId) as PendingSplit)
                        .pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, coffeeAlicePromptId, "15")

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val dinnerPromptMessageId =
                    fixture.splitStateStore
                        .listAll(CHAT_ID)
                        .filterIsInstance<PendingSplit>()
                        .single { it.promptMessageId != coffeePromptMessageId }
                        .promptMessageId
                fixture.tapExact(aliceId, groupId, dinnerPromptMessageId)

                val dinnerActionsMessageId =
                    (fixture.splitStateStore.find(CHAT_ID, dinnerPromptMessageId) as PendingSplit).actionsMessageId!!
                fixture.callbackHandler.handle(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq", dinnerActionsMessageId, SPLIT_CANCEL_DATA),
                )

                fixture.splitStateStore.find(CHAT_ID, dinnerPromptMessageId) shouldBe null
                (fixture.splitStateStore.find(CHAT_ID, coffeePromptMessageId) as PendingSplit)
                    .amountsEntered shouldBe mapOf(aliceId to BigDecimal("15.00"))
            }
        }
    })
