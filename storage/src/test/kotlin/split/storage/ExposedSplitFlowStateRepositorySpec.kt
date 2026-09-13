package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import java.math.BigDecimal
import java.time.Instant

class ExposedSplitFlowStateRepositorySpec :
    StringSpec({

        val group = GroupId("g1")
        val otherGroup = GroupId("g2")
        val alice = MemberId("alice")
        val bob = MemberId("bob")

        suspend fun seedGroupAndMembers(db: Database) {
            ExposedGroupRepository(db).create(Group(group, "USD", Instant.parse("2026-09-04T00:00:00Z")))
            ExposedGroupRepository(db).create(Group(otherGroup, "USD", Instant.parse("2026-09-04T00:00:00Z")))
            ExposedMemberRepository(db).create(Member(alice, "Alice"))
            ExposedMemberRepository(db).create(Member(bob, "Bob"))
        }

        fun aSplitRow(
            chatId: Long = -100,
            promptMessageId: Long = 1,
        ) = SplitFlowStateRow(
            chatId = chatId,
            groupId = group,
            stateType = SplitFlowStateType.SPLIT,
            invokerId = alice,
            promptMessageId = promptMessageId,
            description = "dinner",
            amount = BigDecimal("90.00"),
            currency = "USD",
            stage = "ENTERING_AMOUNTS",
            participantIds = listOf(alice, bob),
            amountsEntered = mapOf(alice to BigDecimal("50.00")),
            actionsMessageId = 2,
            pendingParticipantId = bob,
            pendingPromptMessageId = 3,
            pendingIsAutoAdvance = true,
        )

        fun aDraftRow(
            chatId: Long = -100,
            promptMessageId: Long = 1,
        ) = SplitFlowStateRow(
            chatId = chatId,
            groupId = group,
            stateType = SplitFlowStateType.DRAFT,
            invokerId = alice,
            promptMessageId = promptMessageId,
            description = null,
            amount = null,
            currency = "USD",
            awaiting = "DESCRIPTION",
            mentionUsernames = emptyList(),
        )

        "writes a SPLIT row and reads it back exactly, via either its prompt or its pending prompt message id" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                val row = aSplitRow()

                repo.upsert(row)

                repo.findByChatAndMessage(-100, 1) shouldBe row
                repo.findByChatAndMessage(-100, 3) shouldBe row
            }
        }

        "writes a DRAFT row and reads it back exactly" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                val row = aDraftRow()

                repo.upsert(row)

                repo.findByChatAndMessage(-100, 1) shouldBe row
            }
        }

        "returns null for a chat/message id pair that matches nothing" {
            withTestDatabase { db ->
                ExposedSplitFlowStateRepository(db).findByChatAndMessage(-100, 999) shouldBe null
            }
        }

        "upserting a second time with the same chatId/promptMessageId replaces the row rather than adding one" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                repo.upsert(aSplitRow())

                val updated =
                    aSplitRow().copy(
                        amountsEntered = mapOf(alice to BigDecimal("50.00"), bob to BigDecimal("40.00")),
                    )
                repo.upsert(updated)

                repo.listByChat(-100) shouldBe listOf(updated)
            }
        }

        "two rows with different prompt message ids in the same chat coexist" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                val dinner = aSplitRow(promptMessageId = 1)
                val drinks = aSplitRow(promptMessageId = 10).copy(description = "drinks")

                repo.upsert(dinner)
                repo.upsert(drinks)

                repo.listByChat(-100).toSet() shouldBe setOf(dinner, drinks)
            }
        }

        "listByChat is scoped to one chat" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                repo.upsert(aSplitRow(chatId = -100))
                repo.upsert(aSplitRow(chatId = -200, promptMessageId = 1))

                repo.listByChat(-100) shouldBe listOf(aSplitRow(chatId = -100))
            }
        }

        "delete removes only the matching row" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                repo.upsert(aSplitRow(promptMessageId = 1))
                repo.upsert(aSplitRow(promptMessageId = 2))

                repo.delete(-100, 1)

                repo.listByChat(-100) shouldBe listOf(aSplitRow(promptMessageId = 2))
            }
        }

        "listSplitsByGroup returns SPLIT rows for the group, excluding DRAFT rows and other groups" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedSplitFlowStateRepository(db)
                val split = aSplitRow()
                repo.upsert(split)
                repo.upsert(aDraftRow(promptMessageId = 2))
                repo.upsert(aSplitRow(chatId = -999, promptMessageId = 3).copy(groupId = otherGroup))

                repo.listSplitsByGroup(group) shouldBe listOf(split)
            }
        }
    })
