package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitFlowReplyHandlerSpec : StringSpec({

    "a valid amount reply fills in the pending participant, edits both messages, and auto-advances to the next unfilled one" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                    pendingParticipantId = bobId, pendingPromptMessageId = 3,
                ),
            )
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 3, text = "40"))

            val flow = flowStore.get(-100)
            flow?.amountsEntered shouldBe mapOf(bobId to BigDecimal("40"))
            flow?.pendingParticipantId shouldBe aliceId
            flow?.pendingPromptMessageId shouldBe 1L
            telegramApi.sentForceReplyPrompts.single().second shouldBe "How much is @alice's share? Reply to this message with an amount."
            telegramApi.editedRichMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 1L }
            telegramApi.editedMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 2L }
        }
    }

    "once every participant has an amount, the flow stops advancing and waits for Confirm" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                    pendingParticipantId = aliceId, pendingPromptMessageId = 3,
                ),
            )
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 3, text = "90"))

            val flow = flowStore.get(-100)
            flow?.amountsEntered shouldBe mapOf(aliceId to BigDecimal("90"))
            flow?.pendingParticipantId shouldBe null
            flow?.pendingPromptMessageId shouldBe null
            telegramApi.sentForceReplyPrompts shouldBe emptyList()
        }
    }

    "an invalid amount reply doesn't touch state and asks again" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                pendingParticipantId = aliceId, pendingPromptMessageId = 3,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 3, text = "not a number"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages.single().second shouldBe "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50."
        }
    }

    "a reply with more than 2 decimal places doesn't touch state and asks again" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                pendingParticipantId = aliceId, pendingPromptMessageId = 3,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 3, text = "33.333"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages.single().second shouldBe "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50."
        }
    }

    "a non-positive amount reply doesn't touch state and asks again" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                pendingParticipantId = aliceId, pendingPromptMessageId = 3,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 3, text = "-5"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages.single().second shouldBe "That doesn't look like an amount — reply with a positive number with at most 2 decimal places, e.g. 42.50."
        }
    }

    "a reply from someone other than the invoker is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                pendingParticipantId = bobId, pendingPromptMessageId = 3,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, bobId, groupId, replyToMessageId = 3, text = "40"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages shouldBe emptyList()
        }
    }

    "a reply to the wrong message is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                pendingParticipantId = aliceId, pendingPromptMessageId = 3,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 999, text = "40"))

            flowStore.get(-100) shouldBe pending
        }
    }

    "a reply when no participant is pending is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = null,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 1, text = "40"))

            flowStore.get(-100) shouldBe pending
        }
    }
})
