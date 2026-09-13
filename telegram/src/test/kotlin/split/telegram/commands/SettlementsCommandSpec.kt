package split.telegram.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Settlement
import split.core.SettlementId
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
import split.telegram.withTestDatabase
import java.math.BigDecimal
import java.time.Instant

class SettlementsCommandSpec :
    StringSpec({

        "lists active settlements oldest first, capped at the 10 most recent, naming who paid by @username" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                settlementRepository.create(
                    Settlement(
                        id = SettlementId("older1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        fromMemberId = aliceId,
                        toMemberId = bobbyId,
                        amount = BigDecimal("10.00"),
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    ),
                )
                settlementRepository.create(
                    Settlement(
                        id = SettlementId("newer1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        fromMemberId = bobbyId,
                        toMemberId = aliceId,
                        amount = BigDecimal("20.00"),
                        createdBy = bobbyId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command = SettlementsCommand(groupRepository, memberRepository, settlementRepository, platformDirectory, telegramApi)

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
                                                        RichBlockTableCell("Date", isHeader = true),
                                                        RichBlockTableCell("From", isHeader = true),
                                                        RichBlockTableCell("To", isHeader = true),
                                                        RichBlockTableCell("Amount", isHeader = true),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("2026-08-27"),
                                                        RichBlockTableCell("@alice"),
                                                        RichBlockTableCell("@bobby"),
                                                        RichBlockTableCell("10.00 USD"),
                                                    ),
                                                    listOf(
                                                        RichBlockTableCell("2026-08-28"),
                                                        RichBlockTableCell("@bobby"),
                                                        RichBlockTableCell("@alice"),
                                                        RichBlockTableCell("20.00 USD"),
                                                    ),
                                                ),
                                            caption = "Last 10 settlements:",
                                        ),
                                    ),
                            ),
                    )
            }
        }

        "falls back to the display name for a member with no known username" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", null, "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                settlementRepository.create(
                    Settlement(
                        id = SettlementId("aaaa1234567890"),
                        groupId = groupId,
                        currency = "USD",
                        fromMemberId = aliceId,
                        toMemberId = bobbyId,
                        amount = BigDecimal("5.00"),
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command = SettlementsCommand(groupRepository, memberRepository, settlementRepository, platformDirectory, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                val row =
                    (
                        telegramApi.sentRichMessages
                            .single()
                            .second.blocks
                            .single() as RichBlockTable
                    ).cells[1]
                row.map { it.text } shouldBe listOf("2026-08-28", "Alice", "@bobby", "5.00 USD")
            }
        }

        "includes settlements recorded in a currency other than the group's default" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobbyId)

                settlementRepository.create(
                    Settlement(
                        id = SettlementId("eur1234567890"),
                        groupId = groupId,
                        currency = "EUR",
                        fromMemberId = aliceId,
                        toMemberId = bobbyId,
                        amount = BigDecimal("8.00"),
                        createdBy = aliceId,
                        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    ),
                )

                val telegramApi = FakeTelegramApi()
                val command = SettlementsCommand(groupRepository, memberRepository, settlementRepository, platformDirectory, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                val row =
                    (
                        telegramApi.sentRichMessages
                            .single()
                            .second.blocks
                            .single() as RichBlockTable
                    ).cells[1]
                row.map { it.text } shouldBe listOf("2026-08-28", "@alice", "@bobby", "8.00 EUR")
            }
        }

        "explains there's nothing yet" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val settlementRepository = ExposedSettlementRepository(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")

                val telegramApi = FakeTelegramApi()
                val command = SettlementsCommand(groupRepository, memberRepository, settlementRepository, platformDirectory, telegramApi)

                command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

                telegramApi.sentRichMessages shouldBe
                    listOf(
                        -100L to
                            InputRichMessage(blocks = listOf(RichBlockParagraph("No settlements recorded yet — use /settle to log one."))),
                    )
            }
        }
    })
