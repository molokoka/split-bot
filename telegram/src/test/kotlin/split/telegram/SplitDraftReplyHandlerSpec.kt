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
import java.math.BigDecimal

private const val DRAFT_CHAT_ID = -100L

private class DraftFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()
    val splitStateStore = SplitStateStore()
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

private suspend fun DraftFixture.aliceAndBobbyInGroup(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100")
    resolver.ensureGroupMembership(groupId, aliceId)
    val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
    return Triple(aliceId, bobbyId, groupId)
}

private suspend fun DraftFixture.aliceOnly(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100")
    resolver.ensureGroupMembership(groupId, aliceId)
    return aliceId to groupId
}

private suspend fun DraftFixture.aliceAndBob(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val bobId = resolver.resolveMember("2", "bob", "Bob")
    val groupId = resolver.resolveGroup("-100")
    resolver.ensureGroupMembership(groupId, aliceId)
    return Triple(aliceId, bobId, groupId)
}

private suspend fun DraftFixture.startSplit(
    memberId: MemberId,
    groupId: GroupId,
    args: String,
) = splitCommand.handle(CommandContext(DRAFT_CHAT_ID, memberId, "1", groupId, args))

private fun DraftFixture.currentDraft() = splitStateStore.get(DRAFT_CHAT_ID) as? PendingSplitDraft

private fun DraftFixture.currentFlow() = splitStateStore.get(DRAFT_CHAT_ID) as? PendingSplit

private suspend fun DraftFixture.answer(
    memberId: MemberId,
    groupId: GroupId,
    replyToMessageId: Long,
    text: String,
) = handler.handle(ReplyContext(DRAFT_CHAT_ID, memberId, groupId, replyToMessageId, text))

private suspend fun DraftFixture.answerAsInvoker(
    memberId: MemberId,
    groupId: GroupId,
    text: String,
) {
    val promptId = currentDraft()!!.promptMessageId
    answer(memberId, groupId, promptId, text)
}

class SplitDraftReplyHandlerSpec :
    StringSpec({

        "completing description, amount, then participants starts the mode-choice flow" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "")
                fixture.answerAsInvoker(aliceId, groupId, "dinner")

                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.AMOUNT
                fixture.answerAsInvoker(aliceId, groupId, "90")

                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.PARTICIPANTS
                fixture.answerAsInvoker(aliceId, groupId, "@bobby")

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentFlow()?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                fixture.currentFlow()?.participantIds shouldBe listOf(aliceId, bobbyId)
            }
        }

        "an equal keyword carried through the draft finishes without a mode-choice tap" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "equal @bobby")
                fixture.answerAsInvoker(aliceId, groupId, "dinner")
                fixture.answerAsInvoker(aliceId, groupId, "90")

                fixture.currentDraft() shouldBe null
                val expense = fixture.expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("45.00"),
                        bobbyId to BigDecimal("45.00"),
                    )
            }
        }

        "an empty description reply doesn't advance the draft" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.startSplit(aliceId, groupId, "")
                fixture.answerAsInvoker(aliceId, groupId, "   ")

                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.DESCRIPTION
                fixture.currentDraft()?.description shouldBe null
            }
        }

        "an invalid amount reply doesn't advance the draft" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.startSplit(aliceId, groupId, "dinner")
                fixture.answerAsInvoker(aliceId, groupId, "not a number")

                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.AMOUNT
                fixture.currentDraft()?.amount shouldBe null
            }
        }

        "an amount reply can override the currency" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.startSplit(aliceId, groupId, "dinner")
                fixture.answerAsInvoker(aliceId, groupId, "90 EUR")

                fixture.currentDraft()?.amount shouldBe BigDecimal("90")
                fixture.currentDraft()?.currency shouldBe "EUR"
                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.PARTICIPANTS
            }
        }

        "an unrecognized mention at the participants step errors and clears the draft" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.startSplit(aliceId, groupId, "90 dinner")
                fixture.answerAsInvoker(aliceId, groupId, "@stranger")

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.telegramApi.sentMessages
                    .last()
                    .second shouldBe
                    "I don't recognize <code>@stranger</code> yet — ask them to run /start with me first."
            }
        }

        "a reply from someone other than the invoker is ignored" {
            withTestDatabase { db ->
                val fixture = DraftFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBob()

                fixture.startSplit(aliceId, groupId, "")
                val draftBefore = fixture.currentDraft()!!

                fixture.answer(bobId, groupId, draftBefore.promptMessageId, "dinner")

                fixture.currentDraft() shouldBe draftBefore
            }
        }
    })
