package split.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.DebtPayment
import split.core.Expense
import split.core.ExpenseHistoryEvent
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.Settlement
import split.core.SettlementHistoryEvent
import split.core.SettlementId
import split.core.SplitType
import java.math.BigDecimal
import java.time.Instant

class MessageFormattingSpec :
    StringSpec({

        val alice = Member(MemberId("alice"), "Alice")
        val bob = Member(MemberId("bob"), "Bob")
        val members = listOf(alice, bob)

        "formatAmount renders two decimal places with the currency code" {
            formatAmount(BigDecimal("90"), "USD") shouldBe "90.00 USD"
            formatAmount(BigDecimal("12.5"), "EUR") shouldBe "12.50 EUR"
        }

        "mentionName prefers a clickable @username over the display name when one is known" {
            mentionName(alice, mapOf(alice.id to "alice_w")) shouldBe "@alice_w"
        }

        "mentionName falls back to the escaped display name when no username is known" {
            mentionName(alice, emptyMap()) shouldBe "Alice"
            mentionName(Member(MemberId("m1"), "<script>"), emptyMap()) shouldBe "&lt;script&gt;"
        }

        "formatExpenseConfirmation names participants by @username once known, falling back per-member otherwise" {
            val expense =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
                )

            formatExpenseConfirmation(expense, members, usernames = mapOf(alice.id to "alice_w")) shouldBe
                "Expense added:\n\n@alice_w paid 90.00 USD for <b>dinner</b>, split equally: @alice_w (45.00), Bob (45.00)"
        }

        "formatExpenseConfirmation names payer, amount, currency, and split type, including the payer's own share" {
            val expense =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "EUR",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
                )

            formatExpenseConfirmation(expense, members) shouldBe
                "Expense added:\n\nAlice paid 90.00 EUR for <b>dinner</b>, split equally: Alice (45.00), Bob (45.00)"
        }

        "formatExpenseConfirmation names the split type for EXACT and SHARES splits too" {
            val exact =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EXACT,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
                )
            val shares = exact.copy(splitType = SplitType.SHARES)

            formatExpenseConfirmation(exact, members) shouldBe
                "Expense added:\n\nAlice paid 90.00 USD for <b>dinner</b>, split by exact amounts: Alice (50.00), Bob (40.00)"
            formatExpenseConfirmation(shares, members) shouldBe
                "Expense added:\n\nAlice paid 90.00 USD for <b>dinner</b>, split by shares: Alice (50.00), Bob (40.00)"
        }

        "formatExpenseConfirmation omits the split clause entirely when the payer is the only participant" {
            val expense =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "solo lunch",
                    amount = BigDecimal("12.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("12.00"))),
                )

            formatExpenseConfirmation(expense, members) shouldBe "Expense added:\n\nAlice paid 12.00 USD for <b>solo lunch</b>"
        }

        "formatExpenseConfirmation fails loudly if the payer isn't in the members list" {
            val expense =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = MemberId("not-a-member"),
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("90.00"))),
                )

            shouldThrow<NoSuchElementException> { formatExpenseConfirmation(expense, members) }
        }

        "buildExpenseListMessage shows a short id, date, and per-person share breakdown for an equal split" {
            val expense =
                Expense(
                    id = ExpenseId("abcdef1234567890"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T14:30:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
                )

            buildExpenseListMessage(listOf(expense), members) shouldBe
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
                                            RichBlockTableCell("dinner\n90.00 USD · paid by Alice\nabcdef12 · 2026-08-28"),
                                            RichBlockTableCell("equally:\nAlice 45.00 USD\nBob 45.00 USD"),
                                        ),
                                    ),
                                caption = "Last 10 expenses:",
                            ),
                        ),
                )
        }

        "buildExpenseListMessage shows the real per-person amounts for an exact split, not an equal guess" {
            val expense =
                Expense(
                    id = ExpenseId("abcdef1234567890"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "rent",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EXACT,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
                )

            val message = buildExpenseListMessage(listOf(expense), members)
            val row = (message.blocks.single() as RichBlockTable).cells[1]

            row.map { it.text } shouldBe
                listOf(
                    "rent\n90.00 USD · paid by Alice\nabcdef12 · 2026-08-28",
                    "by exact amounts:\nAlice 50.00 USD\nBob 40.00 USD",
                )
        }

        "buildExpenseListMessage shows the real per-person amounts for a shares split" {
            val expense =
                Expense(
                    id = ExpenseId("abcdef1234567890"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "groceries",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.SHARES,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("60.00")), ExpenseShare(bob.id, BigDecimal("30.00"))),
                )

            val message = buildExpenseListMessage(listOf(expense), members)
            val row = (message.blocks.single() as RichBlockTable).cells[1]

            row.map { it.text } shouldBe
                listOf(
                    "groceries\n90.00 USD · paid by Alice\nabcdef12 · 2026-08-28",
                    "by shares:\nAlice 60.00 USD\nBob 30.00 USD",
                )
        }

        "buildExpenseListMessage explains there's nothing yet" {
            buildExpenseListMessage(emptyList(), members) shouldBe
                InputRichMessage(blocks = listOf(RichBlockParagraph("No expenses yet — use /split to log one.")))
        }

        "buildSettlementListMessage shows the date, payer, payee, and amount of each settlement" {
            val settlement =
                Settlement(
                    id = SettlementId("s1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    fromMemberId = bob.id,
                    toMemberId = alice.id,
                    amount = BigDecimal("30.00"),
                    createdBy = bob.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                )

            buildSettlementListMessage(listOf(settlement), members) shouldBe
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
                                            RichBlockTableCell("2026-08-28"),
                                            RichBlockTableCell("Bob"),
                                            RichBlockTableCell("Alice"),
                                            RichBlockTableCell("30.00 USD"),
                                        ),
                                    ),
                                caption = "Last 10 settlements:",
                            ),
                        ),
                )
        }

        "buildSettlementListMessage names members by @username once known" {
            val settlement =
                Settlement(
                    id = SettlementId("s1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    fromMemberId = bob.id,
                    toMemberId = alice.id,
                    amount = BigDecimal("30.00"),
                    createdBy = bob.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                )

            val row =
                (
                    buildSettlementListMessage(
                        listOf(settlement),
                        members,
                        usernames = mapOf(bob.id to "bobby"),
                    ).blocks.single() as RichBlockTable
                ).cells[1]

            row.map { it.text } shouldBe listOf("2026-08-28", "@bobby", "Alice", "30.00 USD")
        }

        "buildSettlementListMessage explains there's nothing yet" {
            buildSettlementListMessage(emptyList(), members) shouldBe
                InputRichMessage(blocks = listOf(RichBlockParagraph("No settlements recorded yet — use /settle to log one.")))
        }

        "formatBalances phrases payments relative to the viewer" {
            val payments = mapOf("USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))))

            formatBalances(payments, members, viewerId = alice.id) shouldBe "Balances:\n\nBob owes you 30.00 USD"
            formatBalances(payments, members, viewerId = bob.id) shouldBe "Balances:\n\nYou owe Alice 30.00 USD"
        }

        "formatBalances names a member by @username once one is known" {
            val payments = mapOf("USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))))

            formatBalances(payments, members, viewerId = alice.id, usernames = mapOf(bob.id to "bobby")) shouldBe
                "Balances:\n\n@bobby owes you 30.00 USD"
        }

        "formatBalances says everyone's settled up when there's nothing relevant" {
            formatBalances(emptyMap(), members, viewerId = alice.id) shouldBe "You're all settled up!"
        }

        "formatBalances lists every currency the viewer has a stake in, skipping any that are settled up" {
            val payments =
                mapOf(
                    "USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))),
                    "EUR" to emptyList(),
                    "GBP" to listOf(DebtPayment(from = alice.id, to = bob.id, amount = BigDecimal("20.00"))),
                )

            formatBalances(payments, members, viewerId = alice.id) shouldBe
                "Balances:\n\nYou owe Bob 20.00 GBP\nBob owes you 30.00 USD"
        }

        "formatBalances fails loudly if a payment references a member outside the group" {
            val payments = mapOf("USD" to listOf(DebtPayment(from = MemberId("not-a-member"), to = alice.id, amount = BigDecimal("30.00"))))

            shouldThrow<NoSuchElementException> { formatBalances(payments, members, viewerId = alice.id) }
        }

        "formatSettleSuggestions lists every payment" {
            val payments = mapOf("USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))))

            formatSettleSuggestions(payments, members) shouldBe "Suggested settlements:\n\nBob to pay Alice 30.00 USD"
        }

        "formatSettleSuggestions names members by @username once known" {
            val payments = mapOf("USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))))

            formatSettleSuggestions(
                payments,
                members,
                usernames = mapOf(bob.id to "bobby", alice.id to "alice_w"),
            ) shouldBe "Suggested settlements:\n\n@bobby to pay @alice_w 30.00 USD"
        }

        "formatSettleSuggestions says everyone's settled up when there's nothing to do" {
            formatSettleSuggestions(emptyMap(), members) shouldBe "Everyone's settled up — nothing to do!"
        }

        "formatSettleSuggestions lists every currency that still needs a payment, skipping settled ones" {
            val payments =
                mapOf(
                    "USD" to listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00"))),
                    "EUR" to emptyList(),
                    "GBP" to listOf(DebtPayment(from = alice.id, to = bob.id, amount = BigDecimal("20.00"))),
                )

            formatSettleSuggestions(payments, members) shouldBe
                "Suggested settlements:\n\nAlice to pay Bob 20.00 GBP\nBob to pay Alice 30.00 USD"
        }

        "buildHistoryMessage shows the actual pairwise debt after an expense and a settlement, oldest first" {
            val dinner =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
                )
            val settlement =
                Settlement(
                    id = SettlementId("s1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    fromMemberId = bob.id,
                    toMemberId = alice.id,
                    amount = BigDecimal("45.00"),
                    createdBy = bob.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                )
            val events =
                listOf(
                    ExpenseHistoryEvent(dinner, mapOf(alice.id to BigDecimal("45.00"), bob.id to BigDecimal("-45.00"))),
                    SettlementHistoryEvent(settlement, mapOf(alice.id to BigDecimal("0.00"), bob.id to BigDecimal("0.00"))),
                )

            buildHistoryMessage(events, members) shouldBe
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
                                            RichBlockTableCell("2026-08-27 · dinner — Alice paid 90.00 USD"),
                                            RichBlockTableCell("Bob owes Alice 45.00 USD"),
                                        ),
                                        listOf(
                                            RichBlockTableCell("2026-08-28 · Bob paid Alice 45.00 USD"),
                                            RichBlockTableCell("Everyone's settled up in USD"),
                                        ),
                                    ),
                                caption = "Last 20 events:",
                            ),
                        ),
                )
        }

        "buildHistoryMessage names who owes whom even with three or more members, not just a net figure" {
            val carol = Member(MemberId("carol"), "Carol")
            val threeMembers = listOf(alice, bob, carol)
            val dinner =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("90.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    shares =
                        listOf(
                            ExpenseShare(alice.id, BigDecimal("30.00")),
                            ExpenseShare(bob.id, BigDecimal("30.00")),
                            ExpenseShare(carol.id, BigDecimal("30.00")),
                        ),
                )
            val events =
                listOf(
                    ExpenseHistoryEvent(
                        dinner,
                        mapOf(alice.id to BigDecimal("60.00"), bob.id to BigDecimal("-30.00"), carol.id to BigDecimal("-30.00")),
                    ),
                )

            val row = (buildHistoryMessage(events, threeMembers).blocks.single() as RichBlockTable).cells[1]
            row.map { it.text } shouldBe
                listOf(
                    "2026-08-27 · dinner — Alice paid 90.00 USD",
                    "Bob owes Alice 30.00 USD\nCarol owes Alice 30.00 USD",
                )
        }

        "buildHistoryMessage names members by @username once known" {
            val settlement =
                Settlement(
                    id = SettlementId("s1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    fromMemberId = bob.id,
                    toMemberId = alice.id,
                    amount = BigDecimal("45.00"),
                    createdBy = bob.id,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                )
            val events = listOf(SettlementHistoryEvent(settlement, mapOf(alice.id to BigDecimal("45.00"), bob.id to BigDecimal("-45.00"))))

            val row =
                (
                    buildHistoryMessage(events, members, usernames = mapOf(bob.id to "bobby")).blocks.single()
                        as RichBlockTable
                ).cells[1]
            row.map { it.text } shouldBe listOf("2026-08-28 · @bobby paid Alice 45.00 USD", "@bobby owes Alice 45.00 USD")
        }

        "buildHistoryMessage shows everyone's settled up when a member hasn't been part of any event yet" {
            val expense =
                Expense(
                    id = ExpenseId("e1"),
                    groupId = GroupId("g1"),
                    currency = "USD",
                    description = "coffee",
                    amount = BigDecimal("5.00"),
                    payerId = alice.id,
                    splitType = SplitType.EQUAL,
                    createdBy = alice.id,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    shares = listOf(ExpenseShare(alice.id, BigDecimal("5.00"))),
                )
            val events = listOf(ExpenseHistoryEvent(expense, mapOf(alice.id to BigDecimal("0.00"))))

            val row = (buildHistoryMessage(events, members).blocks.single() as RichBlockTable).cells[1]
            row.map { it.text } shouldBe listOf("2026-08-27 · coffee — Alice paid 5.00 USD", "Everyone's settled up in USD")
        }

        "buildHistoryMessage explains there's nothing yet" {
            buildHistoryMessage(emptyList(), members) shouldBe
                InputRichMessage(blocks = listOf(RichBlockParagraph("No history yet — use /split or /settle to get started.")))
        }
    })
