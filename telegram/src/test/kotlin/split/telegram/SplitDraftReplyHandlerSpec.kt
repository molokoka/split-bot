package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import java.math.BigDecimal

class SplitDraftReplyHandlerSpec :
    StringSpec({

        "completing description, amount, then participants starts the mode-choice flow" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
                var promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId
                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "dinner"))

                (splitStateStore.get(-100) as? PendingSplitDraft)?.awaiting shouldBe SplitDraftField.AMOUNT
                promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId
                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90"))

                (splitStateStore.get(-100) as? PendingSplitDraft)?.awaiting shouldBe SplitDraftField.PARTICIPANTS
                promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId
                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "@bobby"))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                (splitStateStore.get(-100) as? PendingSplit)?.stage shouldBe SplitFlowStage.CHOOSING_MODE
                (splitStateStore.get(-100) as? PendingSplit)?.participantIds shouldBe listOf(aliceId, bobbyId)
            }
        }

        "an equal keyword carried through the draft finishes without a mode-choice tap" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "equal @bobby"))
                var promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId
                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "dinner"))

                promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId
                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90"))

                splitStateStore.get(-100) shouldBe null
                val expense = expenseRepository.listActive(groupId).single()
                expense.shares.associate { it.memberId to it.shareAmount } shouldBe
                    mapOf(
                        aliceId to BigDecimal("45.00"),
                        bobbyId to BigDecimal("45.00"),
                    )
            }
        }

        "an empty description reply doesn't advance the draft" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
                val promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId

                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "   "))

                (splitStateStore.get(-100) as? PendingSplitDraft)?.awaiting shouldBe SplitDraftField.DESCRIPTION
                (splitStateStore.get(-100) as? PendingSplitDraft)?.description shouldBe null
            }
        }

        "an invalid amount reply doesn't advance the draft" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "dinner"))
                val promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId

                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "not a number"))

                (splitStateStore.get(-100) as? PendingSplitDraft)?.awaiting shouldBe SplitDraftField.AMOUNT
                (splitStateStore.get(-100) as? PendingSplitDraft)?.amount shouldBe null
            }
        }

        "an amount reply can override the currency" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "dinner"))
                val promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId

                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "90 EUR"))

                (splitStateStore.get(-100) as? PendingSplitDraft)?.amount shouldBe BigDecimal("90")
                (splitStateStore.get(-100) as? PendingSplitDraft)?.currency shouldBe "EUR"
                (splitStateStore.get(-100) as? PendingSplitDraft)?.awaiting shouldBe SplitDraftField.PARTICIPANTS
            }
        }

        "an unrecognized mention at the participants step errors and clears the draft" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner"))
                val promptId = (splitStateStore.get(-100) as PendingSplitDraft).promptMessageId

                handler.handle(ReplyContext(-100, aliceId, groupId, promptId, "@stranger"))

                expenseRepository.listActive(groupId) shouldBe emptyList()
                telegramApi.sentMessages.last().second shouldBe
                    "I don't recognize <code>@stranger</code> yet — ask them to run /start with me first."
            }
        }

        "a reply from someone other than the invoker is ignored" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobId = resolver.resolveMember("2", "bob", "Bob")
                val groupId = resolver.resolveGroup("-100")
                resolver.ensureGroupMembership(groupId, aliceId)

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore()
                val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
                val splitCommand =
                    SplitExpenseCommand(
                        platformDirectory,
                        groupRepository,
                        memberRepository,
                        resolver,
                        telegramApi,
                        splitStateStore,
                        flowStarter,
                    )
                val handler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

                splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, ""))
                val draftBefore = splitStateStore.get(-100) as? PendingSplitDraft

                handler.handle(ReplyContext(-100, bobId, groupId, draftBefore!!.promptMessageId, "dinner"))

                splitStateStore.get(-100) shouldBe draftBefore
            }
        }
    })
