package split.telegram.splitflow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.telegram.FlowFixture
import split.telegram.aliceAndBobbyInGroup
import split.telegram.chat
import split.telegram.currentFlow
import split.telegram.expenseCreatedWith
import split.telegram.expensesPending
import split.telegram.flowFor
import split.telegram.renderChat
import split.telegram.replyToPrompt
import split.telegram.split
import split.telegram.tap
import split.telegram.withTestDatabase
import java.math.BigDecimal

/**
 * End-to-end split flows, asserted against the chat they produce — see [renderChat] for the
 * rendering. Because an edited message shows its latest text where it was first sent, the message
 * a tap changed appears *above* the tap that changed it, which is how it looks when you scroll up.
 */
class SplitFlowSpec :
    StringSpec({

        "the full equal-split flow: choose Equal, expense created immediately" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Equal")

                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split equally: @alice (45.00), @bobby (45.00)
                    [alice] taps [Equal] on #1
                    """.trimIndent()
                fixture.expenseCreatedWith("alice" to BigDecimal("45.00"), "bobby" to BigDecimal("45.00"))
                fixture.currentFlow() shouldBe null
            }
        }

        "the full exact-split flow: choose Exact, enter both amounts, confirm" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.tap("alice", "@alice")
                fixture.replyToPrompt("alice", "50")
                fixture.tap("alice", "@bobby")
                fixture.replyToPrompt("alice", "40")
                fixture.tap("alice", "Confirm")

                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Exact] on #1
                    [alice] taps [@alice] on #1
                    #4 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#4 50
                    [alice] taps [@bobby] on #1
                    #6 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [alice] ↳#6 40
                    #7 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Confirm] on #7
                    """.trimIndent()
                fixture.expenseCreatedWith("alice" to BigDecimal("50.00"), "bobby" to BigDecimal("40.00"))
                fixture.currentFlow() shouldBe null
            }
        }

        "choosing Exact auto-advances through participants with no manual taps needed" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.replyToPrompt("alice", "50")
                fixture.replyToPrompt("alice", "40")
                fixture.tap("alice", "Confirm")

                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#3 50
                    #5 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [alice] ↳#5 40
                    #6 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Confirm] on #6
                    """.trimIndent()
                fixture.expenseCreatedWith("alice" to BigDecimal("50.00"), "bobby" to BigDecimal("40.00"))
            }
        }

        "a participant answers their own auto-advanced prompt and the expense reflects it" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.replyToPrompt("alice", "50")
                fixture.replyToPrompt("bobby", "40")
                fixture.tap("alice", "Confirm")

                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#3 50
                    #5 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [bobby] ↳#5 40
                    #6 bot: Expense added:

                         @alice paid 90.00 USD for <b>dinner</b>, split by exact amounts: @alice (50.00), @bobby (40.00)
                    [alice] taps [Confirm] on #6
                    """.trimIndent()
                fixture.expenseCreatedWith("alice" to BigDecimal("50.00"), "bobby" to BigDecimal("40.00"))
            }
        }

        "a participant finds the split via /expenses pending and answers the prompt it hands them" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.expensesPending("bobby")
                fixture.tap("bobby", "Enter your amount — dinner")
                fixture.replyToPrompt("bobby", "40")

                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot:
                         | Person | Amount    |
                         | @alice | —         |
                         | @bobby | 40.00 USD |
                         [@alice]
                         [@bobby ✓ 40.00 USD]
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [bobby] /expenses pending
                    #4 bot:
                         | Split     | Status   |
                         | dinner    | @alice — |
                         | 90.00 USD | @bobby — |
                         [Enter your amount — dinner]
                    [bobby] taps [Enter your amount — dinner] on #4
                    #5 bot: @alice, someone else is entering their amount now — tap "Enter your amount" again when you're ready.
                    #6 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [bobby] ↳#6 40
                    #7 bot: Entered 40.00 USD of 90.00 USD
                         [Cancel]
                    """.trimIndent()
                fixture.currentFlow()!!.amountsEntered shouldBe mapOf(fixture.idOf("bobby") to BigDecimal("40.00"))
            }
        }

        "claiming a row while someone else has an outstanding prompt keeps their prompt and warns them" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.tap("bobby", "@bobby")
                fixture.replyToPrompt("bobby", "40")

                // #3 is still in the chat: alice's prompt was kept, not deleted out from under her.
                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot:
                         | Person | Amount    |
                         | @alice | —         |
                         | @bobby | 40.00 USD |
                         [@alice]
                         [@bobby ✓ 40.00 USD]
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [bobby] taps [@bobby] on #1
                    #4 bot: @alice, someone else is entering their amount now — tap "Enter your amount" again when you're ready.
                    #5 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [bobby] ↳#5 40
                    #6 bot: Entered 40.00 USD of 90.00 USD
                         [Cancel]
                    """.trimIndent()
            }
        }

        "re-entering a participant's amount before confirming overwrites the earlier value" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.replyToPrompt("alice", "50")
                fixture.replyToPrompt("alice", "40")
                fixture.tap("alice", "@alice ✓ 50.00 USD")
                fixture.replyToPrompt("alice", "60")

                // 60 + 40 overshoots 90, so Confirm is gone from the actions message.
                fixture.chat() shouldBe
                    """
                    [alice] /split 90 dinner @bobby
                    #1 bot:
                         | Person | Amount    |
                         | @alice | 60.00 USD |
                         | @bobby | 40.00 USD |
                         [@alice ✓ 60.00 USD]
                         [@bobby ✓ 40.00 USD]
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#3 50
                    #5 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [alice] ↳#5 40
                    [alice] taps [@alice ✓ 50.00 USD] on #1
                    #7 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#7 60
                    #8 bot: Entered 100.00 USD of 90.00 USD
                         [Cancel]
                    """.trimIndent()
                fixture.currentFlow()!!.amountsEntered shouldBe
                    mapOf(
                        fixture.idOf("alice") to BigDecimal("60.00"),
                        fixture.idOf("bobby") to BigDecimal("40.00"),
                    )
            }
        }

        "a second /split coexists with the first, still-pending flow" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "30 coffee @bobby")
                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Equal", onMessage = fixture.flowFor("coffee")!!.promptMessageId)

                // Confirming coffee left dinner's mode prompt untouched and still waiting.
                fixture.chat() shouldBe
                    """
                    [alice] /split 30 coffee @bobby
                    #1 bot: Expense added:

                         @alice paid 30.00 USD for <b>coffee</b>, split equally: @alice (15.00), @bobby (15.00)
                    [alice] /split 90 dinner @bobby
                    #2 bot: How should this be split?
                         [Equal] [Exact]
                    [alice] taps [Equal] on #1
                    """.trimIndent()
                fixture.expenseCreatedWith("alice" to BigDecimal("15.00"), "bobby" to BigDecimal("15.00"))
                fixture.flowFor("dinner")!!.stage shouldBe SplitFlowStage.CHOOSING_MODE
            }
        }

        "cancelling one of two concurrent flows leaves the other's amounts untouched" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.split("alice", "30 coffee @bobby")
                val coffeePromptMessageId = fixture.flowFor("coffee")!!.promptMessageId
                fixture.tap("alice", "Exact", onMessage = coffeePromptMessageId)
                fixture.replyToPrompt("alice", "15")

                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact", onMessage = fixture.flowFor("dinner")!!.promptMessageId)
                fixture.tap("alice", "Cancel", onMessage = fixture.flowFor("dinner")!!.actionsMessageId!!)

                // Coffee's table, actions and @bobby's open prompt all survive dinner's cancellation.
                fixture.chat() shouldBe
                    """
                    [alice] /split 30 coffee @bobby
                    #1 bot:
                         | Person | Amount    |
                         | @alice | 15.00 USD |
                         | @bobby | —         |
                         [@alice ✓ 15.00 USD]
                         [@bobby]
                    [alice] taps [Exact] on #1
                    #3 bot (force reply): How much is @alice's share? Reply to this message with an amount.
                    [alice] ↳#3 15
                    #4 bot: Entered 15.00 USD of 30.00 USD
                         [Cancel]
                    #5 bot (force reply): How much is @bobby's share? Reply to this message with an amount.
                    [alice] /split 90 dinner @bobby
                    #6 bot: Split cancelled.
                    [alice] taps [Exact] on #6
                    #7 bot: Split cancelled.
                    [alice] taps [Cancel] on #7
                    """.trimIndent()
                fixture.flowFor("dinner") shouldBe null
                fixture.flowFor("coffee")!!.amountsEntered shouldBe
                    mapOf(fixture.idOf("alice") to BigDecimal("15.00"))
            }
        }
    })
