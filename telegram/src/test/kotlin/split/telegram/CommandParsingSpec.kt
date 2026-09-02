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
        parseCommand("/split 90 dinner @alice") shouldBe ("split" to "90 dinner @alice")
    }

    "strips a bot-name suffix like /split@mybot" {
        parseCommand("/split@mybot 90 dinner") shouldBe ("split" to "90 dinner")
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

    "parseSplitArgs with no currency uses the group default" {
        parseSplitArgs("90 dinner @alice @bobby", defaultCurrency = "USD") shouldBe
            SplitExpenseArgs(BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"))
    }

    "parseSplitArgs with an explicit currency overrides the default" {
        parseSplitArgs("90 EUR dinner @alice", defaultCurrency = "USD") shouldBe
            SplitExpenseArgs(BigDecimal("90"), "EUR", "dinner", listOf("alice"))
    }

    "parseSplitArgs keeps a multi-word description that isn't a currency code" {
        parseSplitArgs("90 Fancy Dinner Party @alice", defaultCurrency = "USD") shouldBe
            SplitExpenseArgs(BigDecimal("90"), "USD", "Fancy Dinner Party", listOf("alice"))
    }

    "parseSplitArgs rejects no mentions" {
        shouldThrow<IllegalArgumentException> { parseSplitArgs("90 dinner", defaultCurrency = "USD") }
    }

    "parseSettleArgs parses a mention and an amount" {
        parseSettleArgs("@bobby 20") shouldBe SettleArgs("bobby", BigDecimal("20"))
    }

    "parseSettleArgs rejects zero mentions" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("20") }
    }

    "parseSettleArgs rejects more than one mention" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("@bobby @carol 20") }
    }
})
