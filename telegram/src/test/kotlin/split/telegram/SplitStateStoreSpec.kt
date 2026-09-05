package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedSplitFlowStateRepository
import java.math.BigDecimal
import java.time.Instant

class SplitStateStoreSpec : StringSpec({

    suspend fun seedGroupAndMembers(db: org.jetbrains.exposed.v1.jdbc.Database) {
        ExposedGroupRepository(db).create(split.core.Group(GroupId("g1"), "USD", Instant.parse("2026-09-04T00:00:00Z")))
        ExposedMemberRepository(db).create(split.core.Member(MemberId("alice"), "Alice"))
        ExposedMemberRepository(db).create(split.core.Member(MemberId("bob"), "Bob"))
    }

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

    fun aDraft(promptMessageId: Long = 1) = PendingSplitDraft(
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

    "returns null when there's no matching flow for a chat/message id" {
        withTestDatabase { db ->
            SplitStateStore(ExposedSplitFlowStateRepository(db)).find(-100, 1) shouldBe null
        }
    }

    "returns the flow that was set for a chat, found by its own prompt message id" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            val flow = aFlow()

            store.set(-100, flow)

            store.find(-100, 1) shouldBe flow
        }
    }

    "returns the draft that was set for a chat, found by its prompt message id" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            val draft = aDraft()

            store.set(-100, draft)

            store.find(-100, 1) shouldBe draft
        }
    }

    "two flows with different prompt message ids in the same chat coexist" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            val dinner = aFlow(promptMessageId = 1)
            val drinks = aFlow(promptMessageId = 2)

            store.set(-100, dinner)
            store.set(-100, drinks)

            store.find(-100, 1) shouldBe dinner
            store.find(-100, 2) shouldBe drinks
            store.listAll(-100).toSet() shouldBe setOf(dinner, drinks)
        }
    }

    "setting a state again with the same prompt message id replaces it in place" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            store.set(-100, aFlow(promptMessageId = 1))

            store.set(-100, aFlow(promptMessageId = 1).copy(stage = SplitFlowStage.ENTERING_AMOUNTS))

            (store.find(-100, 1) as PendingSplit).stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
            store.listAll(-100) shouldBe
                listOf(aFlow(promptMessageId = 1).copy(stage = SplitFlowStage.ENTERING_AMOUNTS))
        }
    }

    "clearing one flow removes only that flow, not others in the same chat" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            val dinner = aFlow(promptMessageId = 1)
            val drinks = aFlow(promptMessageId = 2)
            store.set(-100, dinner)
            store.set(-100, drinks)

            store.clear(-100, dinner)

            store.find(-100, 1) shouldBe null
            store.find(-100, 2) shouldBe drinks
        }
    }

    "states for different chats don't interfere" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            store.set(-100, aFlow(promptMessageId = 1))
            store.set(-200, aFlow(promptMessageId = 1))

            store.listAll(-100) shouldBe listOf(aFlow(promptMessageId = 1))
            store.listAll(-200) shouldBe listOf(aFlow(promptMessageId = 1))
        }
    }

    "listOpenSplits returns only ENTERING_AMOUNTS flows for the group" {
        withTestDatabase { db ->
            seedGroupAndMembers(db)
            val store = SplitStateStore(ExposedSplitFlowStateRepository(db))
            val entering = aFlow(promptMessageId = 1).copy(stage = SplitFlowStage.ENTERING_AMOUNTS)
            val choosing = aFlow(promptMessageId = 2)
            store.set(-100, entering)
            store.set(-100, choosing)

            store.listOpenSplits(GroupId("g1")) shouldBe listOf(entering)
        }
    }

    "a flow set before a restart is still found after reconnecting to the same database file" {
        val dbFile = java.nio.file.Files.createTempFile("split-restart-test-", ".db")
        java.nio.file.Files.delete(dbFile)
        val path = dbFile.toString()
        try {
            val db1 = split.storage.connectDatabase(path)
            seedGroupAndMembers(db1)
            SplitStateStore(ExposedSplitFlowStateRepository(db1)).set(-100, aFlow())

            // A fresh Database/connection against the same file stands in for the bot process
            // restarting — nothing here reuses db1's in-memory objects.
            val db2 = split.storage.connectDatabase(path)
            SplitStateStore(ExposedSplitFlowStateRepository(db2)).find(-100, 1) shouldBe aFlow()
        } finally {
            java.nio.file.Files.deleteIfExists(dbFile)
            java.nio.file.Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-wal"))
            java.nio.file.Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-shm"))
        }
    }
})
