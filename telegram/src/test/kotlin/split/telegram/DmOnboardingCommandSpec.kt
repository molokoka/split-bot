package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.MemberId

private const val ADD_TO_GROUP_URL = "https://t.me/split_bot?startgroup=split"

private val ADD_TO_GROUP_KEYBOARD =
    InlineKeyboardMarkup(
        inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "➕ Add me to a group", url = ADD_TO_GROUP_URL))),
    )

private const val DM_START_TEXT =
    "👋 Hi! I split expenses for groups of friends, right inside Telegram.\n\n" +
        "I only work inside a group chat — I can't track anything here in a DM.\n\n" +
        "To get started:\n" +
        "1. Tap the button below and pick a group\n" +
        "2. Once I'm in, everyone who wants to be @mentioned in /split should also send me /start — " +
        "same as you just did here\n" +
        "3. Then just /split an expense and I'll take it from there"

class DmOnboardingCommandSpec :
    StringSpec({

        val context = DmCommandContext(chatId = 555, memberId = MemberId("m1"), externalUserId = "1", args = "")

        "DmStartCommand explains the bot only works in groups and offers an add-to-group button" {
            val telegramApi = FakeTelegramApi()

            DmStartCommand("split_bot", telegramApi).handle(context)

            telegramApi.sentMessages shouldBe listOf(555L to DM_START_TEXT)
            telegramApi.sentKeyboards shouldBe listOf(ADD_TO_GROUP_KEYBOARD)
        }

        "DmFallbackCommand nudges the user to add the bot to a group first" {
            val telegramApi = FakeTelegramApi()

            DmFallbackCommand("split_bot", telegramApi).handle(context)

            telegramApi.sentMessages shouldBe listOf(555L to "I only work inside group chats — add me to one first.")
            telegramApi.sentKeyboards shouldBe listOf(ADD_TO_GROUP_KEYBOARD)
        }
    })
