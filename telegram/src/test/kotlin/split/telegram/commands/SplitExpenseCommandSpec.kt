package split.telegram.commands

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
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.currentFlow
import split.telegram.splitflow.PendingSplit
import split.telegram.splitflow.PendingSplitDraft
import split.telegram.splitflow.SPLIT_MODE_PROMPT
import split.telegram.splitflow.SplitDraftField
import split.telegram.splitflow.SplitFlowStage
import split.telegram.splitflow.SplitFlowStarter
import split.telegram.splitflow.SplitStateStore
import split.telegram.splitflow.splitDraftPromptText
import split.telegram.splitflow.splitModeKeyboard
import split.telegram.withTestDatabase
import java.math.BigDecimal

private const val COMMAND_CHAT_ID = -100L

private class CommandFixture(
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
    val command =
        SplitExpenseCommand(
            platformDirectory,
            groupRepository,
            memberRepository,
            resolver,
            telegramApi,
            splitStateStore,
            flowStarter,
        )
}

private suspend fun CommandFixture.aliceAndBobbyMemberOnly(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100001")
    resolver.ensureGroupMembership(groupId, aliceId)
    val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
    return Triple(aliceId, bobbyId, groupId)
}

private suspend fun CommandFixture.aliceAndBobbyBothMembers(): Triple<MemberId, MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100001")
    resolver.ensureGroupMembership(groupId, aliceId)
    val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
    resolver.ensureGroupMembership(groupId, bobbyId)
    return Triple(aliceId, bobbyId, groupId)
}

private suspend fun CommandFixture.aliceOnly(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100001")
    resolver.ensureGroupMembership(groupId, aliceId)
    return aliceId to groupId
}

private suspend fun CommandFixture.aliceUnregistered(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100001")
    return aliceId to groupId
}

private suspend fun CommandFixture.sendSplit(
    memberId: MemberId,
    groupId: GroupId,
    args: String,
) = command.handle(CommandContext(COMMAND_CHAT_ID, memberId, "1", groupId, args))

private suspend fun CommandFixture.currentState() = splitStateStore.listAll(COMMAND_CHAT_ID).singleOrNull()

private suspend fun CommandFixture.currentDraft() = currentState() as? PendingSplitDraft

private suspend fun CommandFixture.currentFlow() = currentState() as? PendingSplit

class SplitExpenseCommandSpec :
    StringSpec({

        "starts a split-mode choice flow for the sender and mentioned members" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyMemberOnly()

                fixture.sendSplit(aliceId, groupId, "90 dinner @bobby")

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.telegramApi.sentMessages.single() shouldBe (-100L to SPLIT_MODE_PROMPT)
                fixture.telegramApi.sentKeyboards.single() shouldBe splitModeKeyboard()

                val flow = fixture.currentFlow()
                flow?.invokerId shouldBe aliceId
                flow?.groupId shouldBe groupId
                flow?.amount shouldBe BigDecimal("90.00")
                flow?.currency shouldBe "USD"
                flow?.description shouldBe "dinner"
                flow?.participantIds shouldBe listOf(aliceId, bobbyId)
                flow?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                flow?.promptMessageId shouldBe 1L
            }
        }

        "replies with an error and doesn't start a flow for an unrecognized mention" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, groupId) = fixture.aliceUnregistered()

                fixture.sendSplit(aliceId, groupId, "90 dinner @stranger")

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentState() shouldBe null
                fixture.telegramApi.sentMessages
                    .single()
                    .second shouldBe
                    "I don't recognize <code>@stranger</code> yet — ask them to run /start with me first."
            }
        }

        "the equal keyword creates the expense immediately, no flow" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyMemberOnly()

                fixture.sendSplit(aliceId, groupId, "equal 90 dinner @bobby")

                val expense = fixture.expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("45.00"),
                        bobbyId to BigDecimal("45.00"),
                    )
                fixture.currentState() shouldBe null
            }
        }

        "per-mention amounts create the exact-split expense immediately, no flow" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyMemberOnly()

                fixture.sendSplit(aliceId, groupId, "90 dinner @bobby 40")

                val expense = fixture.expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("50.00"),
                        bobbyId to BigDecimal("40.00"),
                    )
                fixture.currentState() shouldBe null
            }
        }

        "the exact keyword with no per-mention amounts jumps straight into entering amounts" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, _, groupId) = fixture.aliceAndBobbyMemberOnly()

                fixture.sendSplit(aliceId, groupId, "exact 90 dinner @bobby")

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.currentFlow()?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
            }
        }

        "a bare /split starts the guided draft, asking for the description first" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.sendSplit(aliceId, groupId, "")

                val draft = fixture.currentDraft()
                draft?.awaiting shouldBe SplitDraftField.DESCRIPTION
                draft?.description shouldBe null
                draft?.amount shouldBe null
                draft?.mentionUsernames shouldBe emptyList()
                fixture.telegramApi.sentForceReplyPrompts
                    .single()
                    .second shouldBe
                    splitDraftPromptText(SplitDraftField.DESCRIPTION, "USD")
            }
        }

        "/split dinner starts the guided draft with the description pre-filled, asking for the amount" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.sendSplit(aliceId, groupId, "dinner")

                val draft = fixture.currentDraft()
                draft?.awaiting shouldBe SplitDraftField.AMOUNT
                draft?.description shouldBe "dinner"
            }
        }

        "/split 90 dinner with no mentions asks for participants, listing known group members" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, _, groupId) = fixture.aliceAndBobbyBothMembers()

                fixture.sendSplit(aliceId, groupId, "90 dinner")

                fixture.currentDraft()?.awaiting shouldBe SplitDraftField.PARTICIPANTS
                fixture.telegramApi.sentForceReplyPrompts
                    .single()
                    .second shouldBe
                    "Who split this with you? Reply with their usernames. Members:\n\n• @bobby"
            }
        }

        "/split @alice @bob starts the guided draft with participants pre-filled, asking for the description" {
            withTestDatabase { db ->
                val fixture = CommandFixture(db)
                val (aliceId, _, groupId) = fixture.aliceAndBobbyMemberOnly()

                fixture.sendSplit(aliceId, groupId, "@bobby")

                val draft = fixture.currentDraft()
                draft?.awaiting shouldBe SplitDraftField.DESCRIPTION
                draft?.mentionUsernames shouldBe listOf("bobby")
            }
        }
    })
