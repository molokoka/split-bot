package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId

class HelpCommandSpec : StringSpec({

    val context = CommandContext(
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

    "StartCommand sends a greeting followed by the help text" {
        val telegramApi = FakeTelegramApi()

        StartCommand(telegramApi).handle(context)

        telegramApi.sentMessages shouldBe listOf(-1L to "Hi! I'll help you split expenses in this group.\n\n$HELP_TEXT")
    }
})
