package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSplitFlowStateRepository
import java.math.BigDecimal
import java.time.Instant

class ExpensesCommandSpec :
    StringSpec({

        "lists active expenses oldest first, capped at the 10 most recent, naming who paid and who participated by @username" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                // ids are unrelated to their descriptions on purpose, to make it obvious in the
                // expected output below which part is the 8-char id prefix vs. the description
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("older1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "lunch",
                        amount = BigDecimal("10.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00"))),
                    ),
                )
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("newer1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("20.00"),
                        payerId = bobbyId,
                        splitType = SplitType.EQUAL,
                        createdBy = bobbyId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobbyId, BigDecimal("10.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val command =
                    ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                telegramApi.sentRichMessages shouldBe
                    listOf(
                        -100L to
                            InputRichMessage(
                                blocks =
                                    listOf(
                                        RichBlockTable(
                                            cells =
                                                listOf(
                                                    listOf(
                                                        RichBlockTableCell("Expense", isHeader = true),
                                                        RichBlockTableCell("Split", isHeader = true),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("lunch\n10.00 USD · paid by @alice\nolder123 · 2026-08-27"),
                                                        RichBlockTableCell("equally:\n@alice 10.00 USD"),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("dinner\n20.00 USD · paid by @bobby\nnewer123 · 2026-08-28"),
                                                        RichBlockTableCell("equally:\n@alice 10.00 USD\n@bobby 10.00 USD"),
                                                    ),
                                                ),
                                            caption = "Last 10 expenses:",
                                        ),
                                    ),
                            ),
                    )
            }
        }

        "shows the real per-person breakdown for exact and shares splits, not just equal" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                expenseRepository.create(
                    Expense(
                        id = ExpenseId("aaaa1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "rent",
                        amount = BigDecimal("100.00"),
                        payerId = aliceId,
                        splitType = SplitType.EXACT,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("60.00")), ExpenseShare(bobbyId, BigDecimal("40.00"))),
                    ),
                )
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("bbbb1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "utilities",
                        amount = BigDecimal("90.00"),
                        payerId = bobbyId,
                        splitType = SplitType.SHARES,
                        createdBy = bobbyId,
                        createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobbyId, BigDecimal("60.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val command =
                    ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                val rows =
                    (
                        telegramApi.sentRichMessages
                            .single()
                            .second.blocks
                            .single() as RichBlockTable
                    ).cells.drop(1)
                rows.map { row -> row.map { it.text } } shouldBe
                    listOf(
                        listOf(
                            "rent\n100.00 USD · paid by @alice\naaaa1234 · 2026-08-28",
                            "by exact amounts:\n@alice 60.00 USD\n@bobby 40.00 USD",
                        ),
                        listOf(
                            "utilities\n90.00 USD · paid by @bobby\nbbbb1234 · 2026-08-29",
                            "by shares:\n@alice 30.00 USD\n@bobby 60.00 USD",
                        ),
                    )
            }
        }

        "falls back to the display name for a member with no known username" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", null, "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)

                expenseRepository.create(
                    Expense(
                        id = ExpenseId("aaaa1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        description = "coffee",
                        amount = BigDecimal("5.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("5.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val command =
                    ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                val row =
                    (
                        telegramApi.sentRichMessages
                            .single()
                            .second.blocks
                            .single() as RichBlockTable
                    ).cells[1]
                row.map { it.text } shouldBe
                    listOf(
                        "coffee\n5.00 USD · paid by Alice\naaaa1234 · 2026-08-28",
                        "equally:\nAlice 5.00 USD",
                    )
            }
        }

        "includes expenses logged in a currency other than the group's default" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)

                // group's default currency is USD (set by resolveGroup) — this expense uses EUR,
                // exactly the case that used to silently vanish from every currency-filtered read.
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("eur1234567890"),
                        groupId = groupId,
                        currency = "EUR",
                        description = "hotel",
                        amount = BigDecimal("120.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("120.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val command =
                    ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                val row =
                    (
                        telegramApi.sentRichMessages
                            .single()
                            .second.blocks
                            .single() as RichBlockTable
                    ).cells[1]
                row.map { it.text } shouldBe
                    listOf("hotel\n120.00 EUR · paid by @alice\neur12345 · 2026-08-28", "equally:\n@alice 120.00 EUR")
            }
        }

        "explains there's nothing yet" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")

                val telegramApi = FakeTelegramApi()
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val command =
                    ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                telegramApi.sentRichMessages shouldBe
                    listOf(
                        -100L to InputRichMessage(blocks = listOf(RichBlockParagraph("No expenses yet — use /split to log one."))),
                    )
            }
        }

        "expenses pending lists an open split, showing who still owes an answer" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()
                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")

                fixture.expensesPending("alice")

                fixture.chat(tail = 2) shouldBe
                    """
                    [alice] /expenses pending
                    #4 bot:
                         | Split     | Status   |
                         | dinner    | @alice — |
                         | 90.00 USD | @bobby — |
                         [Enter your amount — dinner]
                    """.trimIndent()
            }
        }

        "expenses pending still lists a flow whose amounts are all in, until someone confirms it" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()
                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.replyToPrompt("alice", "50")
                fixture.replyToPrompt("alice", "40")

                fixture.expensesPending("bobby")

                // 50 + 40 == 90 and nobody is being prompted any more, but until Alice taps
                // Confirm the split is still open, so it stays listed — with no dashes left.
                fixture.chat(tail = 2) shouldBe
                    """
                    [bobby] /expenses pending
                    #7 bot:
                         | Split     | Status           |
                         | dinner    | @alice 50.00 USD |
                         | 90.00 USD | @bobby 40.00 USD |
                         [Enter your amount — dinner]
                    """.trimIndent()
            }
        }

        "expenses pending says so when nothing is open" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()

                fixture.expensesPending("alice")

                fixture.chat() shouldBe
                    """
                    [alice] /expenses pending
                    #1 bot:
                         No pending splits.
                    """.trimIndent()
            }
        }

        "expenses pending drops a flow once it's been confirmed" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                fixture.aliceAndBobbyInGroup()
                fixture.split("alice", "90 dinner @bobby")
                fixture.tap("alice", "Exact")
                fixture.replyToPrompt("alice", "50")
                fixture.replyToPrompt("alice", "40")
                fixture.tap("alice", "Confirm")

                fixture.expensesPending("alice")

                fixture.chat(tail = 2) shouldBe
                    """
                    [alice] /expenses pending
                    #7 bot:
                         No pending splits.
                    """.trimIndent()
            }
        }
    })
