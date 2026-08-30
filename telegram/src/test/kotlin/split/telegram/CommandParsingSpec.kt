package split.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

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

    "parseAddArgs with no currency uses the group default" {
        parseAddArgs("90 dinner @alice @bobby", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"))
    }

    "parseAddArgs with an explicit currency overrides the default" {
        parseAddArgs("90 EUR dinner @alice", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "EUR", "dinner", listOf("alice"))
    }

    "parseAddArgs keeps a multi-word description that isn't a currency code" {
        parseAddArgs("90 Fancy Dinner Party @alice", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "USD", "Fancy Dinner Party", listOf("alice"))
    }

    "parseAddArgs rejects no mentions" {
        shouldThrow<IllegalArgumentException> { parseAddArgs("90 dinner", defaultCurrency = "USD") }
    }
})
