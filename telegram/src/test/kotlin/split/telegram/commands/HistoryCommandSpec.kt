package split.telegram.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.Settlement
import split.core.SettlementId
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.api.InputRichMessage
import split.telegram.api.RichBlockParagraph
import split.telegram.api.RichBlockTable
import split.telegram.api.RichBlockTableCell
import split.telegram.split
import split.telegram.withTestDatabase
import java.math.BigDecimal
import java.time.Instant

class HistoryCommandSpec :
    StringSpec({

        "shows each member's running balance after every split and settlement, oldest first" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobId = resolver.resolveMember("2", "bob", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobId)

                expenseRepository.create(
                    Expense(
                        id = ExpenseId("e1"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("90.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("45.00")), ExpenseShare(bobId, BigDecimal("45.00"))),
                    ),
                )
                settlementRepository.create(
                    Settlement(
                        id = SettlementId("s1"),
                        groupId = groupId,
                        currency = "USD",
                        fromMemberId = bobId,
                        toMemberId = aliceId,
                        amount = BigDecimal("45.00"),
                        createdBy = bobId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command =
                    HistoryCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

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
                                                        RichBlockTableCell("Event", isHeader = true),
                                                        RichBlockTableCell("Balances", isHeader = true),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("2026-08-27 · dinner — @alice paid 90.00 USD"),
                                                        RichBlockTableCell("@bob owes @alice 45.00 USD"),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("2026-08-28 · @bob paid @alice 45.00 USD"),
                                                        RichBlockTableCell("Everyone's settled up in USD"),
                                                    ),
                                                ),
                                            caption = "Last 20 events:",
                                        ),
                                    ),
                            ),
                    )
            }
        }

        "tracks each currency as an independent ledger, interleaved chronologically" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobId = resolver.resolveMember("2", "bob", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobId)

                expenseRepository.create(
                    Expense(
                        id = ExpenseId("e1"),
                        groupId = groupId,
                        currency = "USD",
                        description = "dinner",
                        amount = BigDecimal("90.00"),
                        payerId = aliceId,
                        splitType = SplitType.EQUAL,
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("45.00")), ExpenseShare(bobId, BigDecimal("45.00"))),
                    ),
                )
                expenseRepository.create(
                    Expense(
                        id = ExpenseId("e2"),
                        groupId = groupId,
                        currency = "EUR",
                        description = "taxi",
                        amount = BigDecimal("20.00"),
                        payerId = bobId,
                        splitType = SplitType.EQUAL,
                        createdBy = bobId,
                        createdAt = Instant.parse("2026-08-27T12:00:00Z"),
                        shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobId, BigDecimal("10.00"))),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command =
                    HistoryCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

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
                        listOf("2026-08-27 · dinner — @alice paid 90.00 USD", "@bob owes @alice 45.00 USD"),
                        listOf("2026-08-27 · taxi — @bob paid 20.00 EUR", "@alice owes @bob 10.00 EUR"),
                    )
            }
        }

        "explains there's nothing yet" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")

                val telegramApi = FakeTelegramApi()
                val command =
                    HistoryCommand(
                        groupRepository,
                        memberRepository,
                        expenseRepository,
                        settlementRepository,
                        platformDirectory,
                        telegramApi,
                    )

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                telegramApi.sentRichMessages shouldBe
                    listOf(
                        -100L to
                            InputRichMessage(blocks = listOf(RichBlockParagraph("No history yet — use /split or /settle to get started."))),
                    )
            }
        }
    })
