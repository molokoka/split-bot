package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.core.GroupId
import split.core.MemberId

class SplitFlowStoreSpec : StringSpec({

    fun aFlow(promptMessageId: Long = 1) = PendingSplit(
        invokerId = MemberId("alice"),
        groupId = GroupId("g1"),
        amount = BigDecimal("90.00"),
        currency = "USD",
        description = "dinner",
        participantIds = listOf(MemberId("alice"), MemberId("bob")),
        promptMessageId = promptMessageId,
        stage = SplitFlowStage.CHOOSING_MODE,
    )

    "returns null when there's no pending flow for a chat" {
        SplitFlowStore().get(-100) shouldBe null
    }

    "returns the flow that was set for a chat" {
        val store = SplitFlowStore()
        val flow = aFlow()

        store.set(-100, flow)

        store.get(-100) shouldBe flow
    }

    "clearing a chat's flow removes it" {
        val store = SplitFlowStore()
        store.set(-100, aFlow())

        store.clear(-100)

        store.get(-100) shouldBe null
    }

    "flows for different chats don't interfere" {
        val store = SplitFlowStore()
        store.set(-100, aFlow(promptMessageId = 1))
        store.set(-200, aFlow(promptMessageId = 2))

        store.get(-100)?.promptMessageId shouldBe 1
        store.get(-200)?.promptMessageId shouldBe 2
    }

    "setting a new flow for a chat replaces the old one" {
        val store = SplitFlowStore()
        store.set(-100, aFlow(promptMessageId = 1))

        store.set(-100, aFlow(promptMessageId = 2))

        store.get(-100)?.promptMessageId shouldBe 2
    }
})
