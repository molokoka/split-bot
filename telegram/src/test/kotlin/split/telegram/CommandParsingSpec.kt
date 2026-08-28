package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CommandParsingSpec : StringSpec({

    "parses a bare command with no args" {
        parseCommand("/help") shouldBe ("help" to "")
    }

    "parses a command with args" {
        parseCommand("/add 90 dinner @alice") shouldBe ("add" to "90 dinner @alice")
    }

    "strips a bot-name suffix like /add@mybot" {
        parseCommand("/add@mybot 90 dinner") shouldBe ("add" to "90 dinner")
    }

    "lowercases the command name" {
        parseCommand("/ADD 90 dinner") shouldBe ("add" to "90 dinner")
    }

    "extracts multiple mentions" {
        extractMentions("split with @alice_w and @bob99") shouldBe listOf("alice_w", "bob99")
    }

    "returns no mentions when there are none" {
        extractMentions("just a plain description") shouldBe emptyList()
    }
})
