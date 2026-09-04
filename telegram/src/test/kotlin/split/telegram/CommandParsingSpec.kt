package split.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.SplitType
import java.math.BigDecimal

class CommandParsingSpec :
    StringSpec({

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
                PartialSplitArgs(null, BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"), null)
        }

        "parseSplitArgs with an explicit currency overrides the default" {
            parseSplitArgs("90 EUR dinner @alice", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(null, BigDecimal("90"), "EUR", "dinner", listOf("alice"), null)
        }

        "parseSplitArgs keeps a multi-word description that isn't a currency code" {
            parseSplitArgs("90 Fancy Dinner Party @alice", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(null, BigDecimal("90"), "USD", "Fancy Dinner Party", listOf("alice"), null)
        }

        "parseSplitArgs leaves mentions empty (not an error) when none are given" {
            parseSplitArgs("90 dinner", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(null, BigDecimal("90"), "USD", "dinner", emptyList(), null)
        }

        "parseSplitArgs leaves amount and description null when the args are empty" {
            parseSplitArgs("", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(null, null, "USD", null, emptyList(), null)
        }

        "parseSplitArgs treats non-numeric leading text as the description, not an amount" {
            parseSplitArgs("dinner @alice", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(null, null, "USD", "dinner", listOf("alice"), null)
        }

        "parseSplitArgs recognizes a leading equal keyword" {
            parseSplitArgs("equal 90 dinner @alice @bobby", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(SplitType.EQUAL, BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"), null)
        }

        "parseSplitArgs recognizes a leading exact keyword" {
            parseSplitArgs("exact 90 dinner @alice @bobby", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(SplitType.EXACT, BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"), null)
        }

        "parseSplitArgs extracts an amount typed right after each mention" {
            parseSplitArgs("90 dinner @alice 50 @bobby 40", defaultCurrency = "USD") shouldBe
                PartialSplitArgs(
                    null,
                    BigDecimal("90"),
                    "USD",
                    "dinner",
                    listOf("alice", "bobby"),
                    listOf(BigDecimal("50"), BigDecimal("40")),
                )
        }

        "parseSplitArgs rejects giving some mentions an amount but not others" {
            shouldThrow<IllegalArgumentException> { parseSplitArgs("90 dinner @alice 50 @bobby", defaultCurrency = "USD") }
        }

        "parseSplitArgs rejects the equal keyword combined with per-mention amounts" {
            shouldThrow<IllegalArgumentException> { parseSplitArgs("equal 90 dinner @alice 50 @bobby 40", defaultCurrency = "USD") }
        }

        "parseSplitArgs rejects a non-positive per-mention amount" {
            shouldThrow<IllegalArgumentException> { parseSplitArgs("90 dinner @alice 0 @bobby 90", defaultCurrency = "USD") }
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
