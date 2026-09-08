package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

private const val WELCOME_TEXT =
    "👋 Hi! I split expenses for this group.\n\n" +
        "One thing first: everyone who'll be @mentioned in /split needs to send me /start too — " +
        "including you, just now. ✅\n\n" +
        "This group's default currency is USD — change it anytime with /currency.\n\n" +
        "Tap below to see everything I can do."

private val WELCOME_KEYBOARD =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(InlineKeyboardButton(text = "📋 All commands", callbackData = HELP_CALLBACK_DATA)),
            ),
    )

class HelpCommandSpec :
    StringSpec({

        val context =
            CommandContext(
                chatId = -1,
                memberId = MemberId("m1"),
                externalUserId = "1",
                groupId = GroupId("g1"),
                args = "",
            )

        "HelpCommand sends the help text" {
            val telegramApi = FakeTelegramApi()

            HelpCommand(telegramApi).handle(context)

            telegramApi.sentMessages shouldBe listOf(-1L to HELP_TEXT)
        }

        "HelpCommand.handleCallback sends the help text and answers the callback query" {
            val telegramApi = FakeTelegramApi()
            val callbackContext =
                CallbackContext(
                    chatId = -1,
                    memberId = MemberId("m1"),
                    groupId = GroupId("g1"),
                    callbackQueryId = "cbq1",
                    messageId = 42,
                    data = HELP_CALLBACK_DATA,
                )

            HelpCommand(telegramApi).handleCallback(callbackContext)

            telegramApi.sentMessages shouldBe listOf(-1L to HELP_TEXT)
            telegramApi.answeredCallbacks shouldBe listOf(Triple("cbq1", null, false))
        }

        "StartCommand leads with the /start-first rule, then the currency, then a commands button" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()

                StartCommand(groupRepository, telegramApi).handle(context.copy(groupId = groupId))

                telegramApi.sentMessages shouldBe listOf(-1L to WELCOME_TEXT)
                telegramApi.sentKeyboards shouldBe listOf(WELCOME_KEYBOARD)
            }
        }

        "StartCommand.welcomeNewGroup sends the same welcome when the bot is added to a group" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100002")
                val telegramApi = FakeTelegramApi()

                val joinContext = GroupJoinContext(chatId = -2, groupId = groupId)
                StartCommand(groupRepository, telegramApi).welcomeNewGroup(joinContext)

                telegramApi.sentMessages shouldBe listOf(-2L to WELCOME_TEXT)
                telegramApi.sentKeyboards shouldBe listOf(WELCOME_KEYBOARD)
            }
        }
    })
