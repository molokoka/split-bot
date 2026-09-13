package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.telegram.api.InlineKeyboardButton
import split.telegram.api.InlineKeyboardMarkup
import split.telegram.api.InputRichMessage
import split.telegram.api.RichBlockParagraph
import split.telegram.api.RichBlockTable
import split.telegram.api.RichBlockTableCell

class ChatTranscriptSpec :
    StringSpec({

        "renders a bot message with its message id" {
            renderChat(listOf(BotSent(messageId = 1, text = "Split cancelled."))) shouldBe
                "#1 bot: Split cancelled."
        }

        "marks a force-reply prompt, since that's what makes it answerable" {
            renderChat(
                listOf(BotSent(messageId = 3, text = "How much is @alice's share?", isForceReply = true)),
            ) shouldBe "#3 bot (force reply): How much is @alice's share?"
        }

        "renders a keyboard as bracketed labels, one line per row" {
            val events =
                listOf(
                    BotSent(
                        messageId = 2,
                        text = "Entered 0.00 USD of 90.00 USD",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard =
                                    listOf(
                                        listOf(
                                            InlineKeyboardButton("Cancel", "split:cancel"),
                                            InlineKeyboardButton("Confirm", "split:confirm"),
                                        ),
                                        listOf(InlineKeyboardButton("Something else", "split:other")),
                                    ),
                            ),
                    ),
                )

            renderChat(events) shouldBe
                """
                #2 bot: Entered 0.00 USD of 90.00 USD
                     [Cancel] [Confirm]
                     [Something else]
                """.trimIndent()
        }

        "shows an edited message's latest text in its original position, like a real chat does" {
            val events =
                listOf(
                    BotSent(messageId = 1, text = "first"),
                    BotSent(messageId = 2, text = "second"),
                    BotEdited(messageId = 1, text = "first, edited"),
                )

            renderChat(events) shouldBe
                """
                #1 bot: first, edited
                #2 bot: second
                """.trimIndent()
        }

        "drops a deleted message entirely" {
            val events =
                listOf(
                    BotSent(messageId = 1, text = "still here"),
                    BotSent(messageId = 2, text = "gone"),
                    BotDeleted(messageId = 2),
                    BotSent(messageId = 3, text = "after"),
                )

            renderChat(events) shouldBe
                """
                #1 bot: still here
                #3 bot: after
                """.trimIndent()
        }

        "renders a rich table with columns wide enough for every cell" {
            val table =
                InputRichMessage(
                    blocks =
                        listOf(
                            RichBlockTable(
                                cells =
                                    listOf(
                                        listOf(
                                            RichBlockTableCell("Person", isHeader = true),
                                            RichBlockTableCell("Amount", isHeader = true),
                                        ),
                                        listOf(RichBlockTableCell("@alice"), RichBlockTableCell("50.00 USD")),
                                        listOf(RichBlockTableCell("@bobby"), RichBlockTableCell("—")),
                                    ),
                            ),
                        ),
                )

            renderChat(listOf(BotSent(messageId = 1, richMessage = table))) shouldBe
                """
                #1 bot:
                     | Person | Amount    |
                     | @alice | 50.00 USD |
                     | @bobby | —         |
                """.trimIndent()
        }

        "spreads a multiline cell over continuation lines within its own row" {
            val table =
                InputRichMessage(
                    blocks =
                        listOf(
                            RichBlockTable(
                                cells =
                                    listOf(
                                        listOf(
                                            RichBlockTableCell("Split", isHeader = true),
                                            RichBlockTableCell("Status", isHeader = true),
                                        ),
                                        listOf(
                                            RichBlockTableCell("dinner\n90.00 USD"),
                                            RichBlockTableCell("@alice 50.00 USD\n@bobby —"),
                                        ),
                                    ),
                            ),
                        ),
                )

            renderChat(listOf(BotSent(messageId = 1, richMessage = table))) shouldBe
                """
                #1 bot:
                     | Split     | Status           |
                     | dinner    | @alice 50.00 USD |
                     | 90.00 USD | @bobby —         |
                """.trimIndent()
        }

        "renders a paragraph block as plain text" {
            val message = InputRichMessage(blocks = listOf(RichBlockParagraph("No pending splits.")))

            renderChat(listOf(BotSent(messageId = 1, richMessage = message))) shouldBe
                """
                #1 bot:
                     No pending splits.
                """.trimIndent()
        }

        "indents the continuation lines of a multiline bot message" {
            renderChat(listOf(BotSent(messageId = 1, text = "dinner — 90.00 USD\npaid by @alice"))) shouldBe
                """
                #1 bot: dinner — 90.00 USD
                     paid by @alice
                """.trimIndent()
        }

        "leaves a blank line in a message blank, rather than padding it with the indent" {
            renderChat(listOf(BotSent(messageId = 1, text = "Expense added:\n\n@alice paid 90.00 USD"))) shouldBe
                "#1 bot: Expense added:\n\n     @alice paid 90.00 USD"
        }

        "renders what each person did: commands, replies and taps" {
            val events =
                listOf(
                    UserSent(actor = "alice", text = "/split 90 dinner @bobby"),
                    BotSent(messageId = 1, text = "How much is @alice's share?", isForceReply = true),
                    UserSent(actor = "alice", text = "50", replyToMessageId = 1),
                    UserTapped(actor = "bobby", buttonLabel = "Confirm", messageId = 2),
                )

            renderChat(events) shouldBe
                """
                [alice] /split 90 dinner @bobby
                #1 bot (force reply): How much is @alice's share?
                [alice] ↳#1 50
                [bobby] taps [Confirm] on #2
                """.trimIndent()
        }

        "renders a callback alert, naming who saw it" {
            renderChat(
                listOf(BotAlerted(actor = "bobby", text = "You're not part of this split.")),
            ) shouldBe """(alert to bobby: "You're not part of this split.")"""
        }

        "renders an alert with no known actor" {
            renderChat(listOf(BotAlerted(actor = null, text = "This split is no longer active."))) shouldBe
                """(alert: "This split is no longer active.")"""
        }

        "tail keeps only the last n entries, so a focused test can pin just its own slice" {
            val events =
                listOf(
                    BotSent(messageId = 1, text = "one"),
                    BotSent(messageId = 2, text = "two"),
                    BotSent(messageId = 3, text = "three"),
                )

            renderChat(events, tail = 2) shouldBe
                """
                #2 bot: two
                #3 bot: three
                """.trimIndent()
        }

        "counts a multi-line message as one entry for tail purposes" {
            val events =
                listOf(
                    BotSent(messageId = 1, text = "one"),
                    BotSent(
                        messageId = 2,
                        text = "two",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard = listOf(listOf(InlineKeyboardButton("Cancel", "split:cancel"))),
                            ),
                    ),
                )

            renderChat(events, tail = 1) shouldBe
                """
                #2 bot: two
                     [Cancel]
                """.trimIndent()
        }

        "finds the visible buttons carrying a label, so a test can tap what it can see" {
            val keyboard =
                InlineKeyboardMarkup(
                    inlineKeyboard = listOf(listOf(InlineKeyboardButton("Cancel", "split:cancel"))),
                )
            val events =
                listOf(
                    BotSent(messageId = 1, text = "coffee actions", keyboard = keyboard),
                    BotSent(messageId = 2, text = "dinner actions", keyboard = keyboard),
                )

            visibleButtons(events, "Cancel") shouldBe listOf(1L to "split:cancel", 2L to "split:cancel")
            visibleButtons(events, "Confirm") shouldBe emptyList()
        }

        "forgets the buttons of a deleted message" {
            val events =
                listOf(
                    BotSent(
                        messageId = 1,
                        text = "actions",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard = listOf(listOf(InlineKeyboardButton("Cancel", "split:cancel"))),
                            ),
                    ),
                    BotDeleted(messageId = 1),
                )

            visibleButtons(events, "Cancel") shouldBe emptyList()
        }

        "takes the buttons from a message's latest edit, not the keyboard it was sent with" {
            val events =
                listOf(
                    BotSent(
                        messageId = 1,
                        text = "actions",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard = listOf(listOf(InlineKeyboardButton("Cancel", "split:cancel"))),
                            ),
                    ),
                    BotEdited(
                        messageId = 1,
                        text = "actions",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard =
                                    listOf(
                                        listOf(
                                            InlineKeyboardButton("Cancel", "split:cancel"),
                                            InlineKeyboardButton("Confirm", "split:confirm"),
                                        ),
                                    ),
                            ),
                    ),
                )

            visibleButtons(events, "Confirm") shouldBe listOf(1L to "split:confirm")
        }

        "labels a button from the message it is on" {
            val events =
                listOf(
                    BotSent(
                        messageId = 4,
                        text = "actions",
                        keyboard =
                            InlineKeyboardMarkup(
                                inlineKeyboard = listOf(listOf(InlineKeyboardButton("Confirm", "split:confirm"))),
                            ),
                    ),
                )

            buttonLabelOn(events, 4, "split:confirm") shouldBe "Confirm"
            buttonLabelOn(events, 4, "split:cancel") shouldBe null
        }

        "points at the newest force-reply prompt, ignoring plain messages and deleted prompts" {
            val events =
                listOf(
                    BotSent(messageId = 1, text = "alice's share?", isForceReply = true),
                    BotSent(messageId = 2, text = "not a prompt"),
                    BotSent(messageId = 3, text = "bobby's share?", isForceReply = true),
                )

            newestForceReplyPrompt(events) shouldBe 3L
            newestForceReplyPrompt(events + BotDeleted(messageId = 3)) shouldBe 1L
            newestForceReplyPrompt(emptyList()) shouldBe null
        }

        "renders an empty chat as an empty string" {
            renderChat(emptyList()) shouldBe ""
        }
    })
