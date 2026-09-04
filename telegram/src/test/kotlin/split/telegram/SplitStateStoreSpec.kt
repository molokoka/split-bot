package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

class SplitStateStoreSpec :
    StringSpec({

        fun aFlow(promptMessageId: Long = 1) =
            PendingSplit(
                invokerId = MemberId("alice"),
                groupId = GroupId("g1"),
                amount = BigDecimal("90.00"),
                currency = "USD",
                description = "dinner",
                participantIds = listOf(MemberId("alice"), MemberId("bob")),
                promptMessageId = promptMessageId,
                stage = SplitFlowStage.CHOOSING_MODE,
            )

        fun aDraft(promptMessageId: Long = 1) =
            PendingSplitDraft(
                invokerId = MemberId("alice"),
                groupId = GroupId("g1"),
                splitTypeHint = null,
                description = null,
                amount = null,
                currency = "USD",
                mentionUsernames = emptyList(),
                exactAmounts = null,
                awaiting = SplitDraftField.DESCRIPTION,
                promptMessageId = promptMessageId,
            )

        "returns null when there's no pending state for a chat" {
            SplitStateStore().get(-100) shouldBe null
        }

        "returns the flow that was set for a chat" {
            val store = SplitStateStore()
            val flow = aFlow()

            store.set(-100, flow)

            store.get(-100) shouldBe flow
        }

        "returns the draft that was set for a chat" {
            val store = SplitStateStore()
            val draft = aDraft()

            store.set(-100, draft)

            store.get(-100) shouldBe draft
        }

        "clearing a chat's state removes it" {
            val store = SplitStateStore()
            store.set(-100, aFlow())

            store.clear(-100)

            store.get(-100) shouldBe null
        }

        "states for different chats don't interfere" {
            val store = SplitStateStore()
            store.set(-100, aFlow(promptMessageId = 1))
            store.set(-200, aFlow(promptMessageId = 2))

            (store.get(-100) as PendingSplit).promptMessageId shouldBe 1
            (store.get(-200) as PendingSplit).promptMessageId shouldBe 2
        }

        "setting a new state for a chat replaces the old one" {
            val store = SplitStateStore()
            store.set(-100, aFlow(promptMessageId = 1))

            store.set(-100, aFlow(promptMessageId = 2))

            (store.get(-100) as PendingSplit).promptMessageId shouldBe 2
        }

        "a draft handed off to a flow replaces it — a chat can never hold both at once" {
            val store = SplitStateStore()
            store.set(-100, aDraft())

            store.set(-100, aFlow())

            store.get(-100) shouldBe aFlow()
        }
    })
