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
import split.storage.ExposedSettlementRepository
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.CallbackContext
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.api.InlineKeyboardButton
import split.telegram.api.InlineKeyboardMarkup
import split.telegram.splitflow.PendingSplitDraft
import split.telegram.splitflow.SplitDraftField
import split.telegram.splitflow.SplitFlowStarter
import split.telegram.splitflow.SplitStateStore
import split.telegram.splitflow.splitDraftPromptText
import split.telegram.withTestDatabase

private const val CHAT_ID = -100L

private val COMMANDS_KEYBOARD =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(
                    InlineKeyboardButton(text = "🧾 Split", callbackData = COMMANDS_SPLIT_CALLBACK_DATA),
                    InlineKeyboardButton(text = "💰 Balance", callbackData = COMMANDS_BALANCE_CALLBACK_DATA),
                    InlineKeyboardButton(text = "🤝 Settle", callbackData = COMMANDS_SETTLE_CALLBACK_DATA),
                ),
                listOf(InlineKeyboardButton(text = "⚙️ Advanced", callbackData = COMMANDS_ADVANCED_CALLBACK_DATA)),
            ),
    )

private class CommandsFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val settlementRepository = ExposedSettlementRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val flowStarter =
        SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
    val splitExpenseCommand =
        SplitExpenseCommand(
            platformDirectory,
            groupRepository,
            memberRepository,
            resolver,
            telegramApi,
            splitStateStore,
            flowStarter,
        )
    val balanceCommand =
        BalanceCommand(
            groupRepository,
            memberRepository,
            expenseRepository,
            settlementRepository,
            platformDirectory,
            telegramApi,
        )
    val settleCommand = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)
    val command = CommandsCommand(telegramApi, balanceCommand, settleCommand, splitExpenseCommand)
}

private suspend fun CommandsFixture.aliceOnly(): Pair<MemberId, GroupId> {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    val groupId = resolver.resolveGroup("-100001")
    resolver.ensureGroupMembership(groupId, aliceId)
    return aliceId to groupId
}

private suspend fun CommandsFixture.currentDraft(): PendingSplitDraft? =
    splitStateStore.listAll(CHAT_ID).singleOrNull() as? PendingSplitDraft

class CommandsCommandSpec :
    StringSpec({

        "CommandsCommand sends the basic commands text with Split, Balance, Settle and Advanced buttons" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handle(CommandContext(CHAT_ID, aliceId, "1", groupId, ""))

                fixture.telegramApi.sentMessages shouldBe listOf(CHAT_ID to COMMANDS_TEXT_BASIC)
                fixture.telegramApi.sentKeyboards shouldBe listOf(COMMANDS_KEYBOARD)
            }
        }

        "CommandsCommand.handleWelcomeCallback sends the basic commands text and answers the callback query" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handleWelcomeCallback(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq1", 42, COMMANDS_CALLBACK_DATA),
                )

                fixture.telegramApi.sentMessages shouldBe listOf(CHAT_ID to COMMANDS_TEXT_BASIC)
                fixture.telegramApi.sentKeyboards shouldBe listOf(COMMANDS_KEYBOARD)
                fixture.telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
            }
        }

        "CommandsCommand.handleAdvancedCallback sends the advanced commands text and answers the callback query" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handleAdvancedCallback(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq1", 42, COMMANDS_ADVANCED_CALLBACK_DATA),
                )

                fixture.telegramApi.sentMessages shouldBe listOf(CHAT_ID to COMMANDS_TEXT_ADVANCED)
                fixture.telegramApi.sentKeyboards shouldBe listOf(null)
                fixture.telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
            }
        }

        "CommandsCommand.handleSplitCallback starts the guided split draft and answers the callback query" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handleSplitCallback(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq1", 42, COMMANDS_SPLIT_CALLBACK_DATA),
                )

                val draft = fixture.currentDraft()
                draft?.awaiting shouldBe SplitDraftField.DESCRIPTION
                fixture.telegramApi.sentForceReplyPrompts
                    .single()
                    .second shouldBe
                    splitDraftPromptText(SplitDraftField.DESCRIPTION, "USD")
                fixture.telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
            }
        }

        "CommandsCommand.handleBalanceCallback shows the tapper's balance and answers the callback query" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handleBalanceCallback(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq1", 42, COMMANDS_BALANCE_CALLBACK_DATA),
                )

                fixture.telegramApi.sentMessages shouldBe listOf(CHAT_ID to "You're all settled up!")
                fixture.telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
            }
        }

        "CommandsCommand.handleSettleCallback shows the /settle usage hint and answers the callback query" {
            withTestDatabase { db ->
                val fixture = CommandsFixture(db)
                val (aliceId, groupId) = fixture.aliceOnly()

                fixture.command.handleSettleCallback(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq1", 42, COMMANDS_SETTLE_CALLBACK_DATA),
                )

                fixture.telegramApi.sentMessages shouldBe
                    listOf(CHAT_ID to "Usage: /settle <code>@person</code> <code>amount</code>")
                fixture.telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
            }
        }
    })
