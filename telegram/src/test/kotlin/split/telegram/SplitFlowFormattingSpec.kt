package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.core.Member
import split.core.MemberId

class SplitFlowFormattingSpec : StringSpec({

    val alice = Member(MemberId("alice"), "Alice")
    val bob = Member(MemberId("bob"), "Bob")
    val nameOf = mapOf(alice.id to alice, bob.id to bob)

    "splitModeKeyboard offers Equal and Exact, no force_reply" {
        splitModeKeyboard() shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "Equal", callbackData = SPLIT_MODE_EQUAL_DATA),
                    InlineKeyboardButton(text = "Exact", callbackData = SPLIT_MODE_EXACT_DATA),
                ),
            ),
        )
    }

    "splitAmountsTable shows a dash for unfilled amounts and the amount for filled ones" {
        val table = splitAmountsTable(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            nameOf = nameOf,
            usernames = emptyMap(),
            currency = "USD",
        )

        val rows = (table.blocks.single() as RichBlockTable).cells
        rows shouldBe listOf(
            listOf(RichBlockTableCell("Person", isHeader = true), RichBlockTableCell("Amount", isHeader = true)),
            listOf(RichBlockTableCell("Alice"), RichBlockTableCell("50.00 USD")),
            listOf(RichBlockTableCell("Bob"), RichBlockTableCell("—")),
        )
    }

    "splitParticipantKeyboard relabels a participant's button once they have an amount, without disabling it" {
        val keyboard = splitParticipantKeyboard(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            nameOf = nameOf,
            usernames = emptyMap(),
            currency = "USD",
        )

        keyboard shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "Alice ✓ 50.00 USD", callbackData = splitPickData(0))),
                listOf(InlineKeyboardButton(text = "Bob", callbackData = splitPickData(1))),
            ),
        )
    }

    "splitAmountPromptText asks for the given name's share" {
        splitAmountPromptText("@alice") shouldBe "How much is @alice's share? Reply to this message with an amount."
    }

    "splitDraftPromptText for participants falls back to a generic example with no known usernames" {
        splitDraftPromptText(SplitDraftField.PARTICIPANTS, "USD") shouldBe
            "Who split this with you? Reply with their usernames, e.g. <code>@alice @bob</code>."
    }

    "splitDraftPromptText for participants lists known usernames as a bulleted, unescaped list" {
        splitDraftPromptText(SplitDraftField.PARTICIPANTS, "USD", knownUsernames = listOf("julia", "marco")) shouldBe
            "Who split this with you? Reply with their usernames. Members:\n\n• @julia\n• @marco"
    }

    "splitActionsText shows the running total against the expense amount" {
        splitActionsText(
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            amount = BigDecimal("90.00"),
            currency = "USD",
        ) shouldBe "Entered 50.00 USD of 90.00 USD"
    }

    "splitActionsKeyboard disables Confirm when canConfirm is false" {
        splitActionsKeyboard(canConfirm = false) shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "Cancel", callbackData = SPLIT_CANCEL_DATA),
                    InlineKeyboardButton(text = "Confirm", callbackData = SPLIT_CONFIRM_DATA, disabled = true),
                ),
            ),
        )
    }

    "splitActionsKeyboard enables Confirm when canConfirm is true" {
        val confirmButton = splitActionsKeyboard(canConfirm = true).inlineKeyboard[0][1]
        confirmButton.disabled shouldBe false
    }

    "splitIsReadyToConfirm is false until every participant has an amount" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe false
    }

    "splitIsReadyToConfirm is false when amounts are all entered but don't sum to the total" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00"), bob.id to BigDecimal("30.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe false
    }

    "splitIsReadyToConfirm is true once amounts are all entered and sum to the total" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00"), bob.id to BigDecimal("40.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe true
    }
})
