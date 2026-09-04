# Self-Service Split Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let any named participant answer their own amount in a `/split` Exact flow (not just the invoker), back the in-flight flow state with SQLite instead of an in-memory map, allow more than one `/split` flow per chat at once, and add `/expenses pending` so a participant can find and answer a flow that's scrolled out of view.

**Architecture:** One new `split_flow_state` table (composite primary key `(chat_id, prompt_message_id)`, which is also each flow's stable identity) replaces `SplitStateStore`'s in-memory map, via a new `SplitFlowStateRepository` port in `storage` and an `ExposedSplitFlowStateRepository` adapter. `SplitStateStore` itself stays the same shape callers already use (`find`/`set`/`clear`/`listOpenSplits`/`listAll`) so almost every existing call site is untouched. Two authorization checks relax from "only the invoker" to "the invoker or the specific participant this concerns." A new `/expenses pending` branch and a new callback prefix reuse the existing `pick`-style "send a fresh prompt" mechanism rather than inventing new reply-matching.

**Tech Stack:** Kotlin, Gradle multi-module (`core`/`storage`/`telegram`), Exposed 1.5.0 + SQLite + Flyway, Kotest 5.9.1 (`StringSpec`), kotlinx.serialization (JSON, runtime-only — no compiler plugin needed in `storage`).

**Spec:** `docs/superpowers/specs/2026-09-04-self-service-split-entry-design.md`

## Global Constraints

- No new domain concept: the finalized expense is always `SplitType.EXACT`, produced by the existing, already-tested `resolveExactSplit` — nothing in `core` changes.
- `split_flow_state` amounts are stored as cents (`Long`), matching `ExpenseTable`'s `amountCents` convention (see `storage/src/main/kotlin/split/storage/Money.kt`'s `toCents()`/`centsToAmount()`).
- No expiry/TTL for `split_flow_state` rows in this iteration — `Cancel` (and `Confirm`) deleting the row is the only way a flow ends.
- Kotest specs stay `StringSpec` with narrative test names and self-descriptive fixture/helper extension functions, matching the existing "read as the spec" convention (`SplitFlowSpec.kt`'s `FlowFixture`, `aliceAndBobbyInGroup()`, `tapExact(...)`). Prefer Kotest's `withData`/`forAll` over copy-pasted near-identical cases where several scenarios only differ by which action races which.
- Every module's tests run via `./gradlew test` (or scoped: `./gradlew :telegram:test --tests "split.telegram.<SpecName>"`, `./gradlew :storage:test --tests "split.storage.<SpecName>"`). `./gradlew check` also gates on ktlint/detekt baselines already configured per module — run it before the final commit of each task.

---

## Task 1: `split_flow_state` storage layer

**Files:**
- Create: `storage/src/main/resources/db/migration/V3__add_split_flow_state.sql`
- Create: `storage/src/main/kotlin/split/storage/SplitFlowState.kt`
- Create: `storage/src/main/kotlin/split/storage/JsonColumns.kt`
- Modify: `storage/src/main/kotlin/split/storage/Tables.kt`
- Create: `storage/src/main/kotlin/split/storage/ExposedSplitFlowStateRepository.kt`
- Modify: `storage/build.gradle.kts`
- Create: `storage/src/test/kotlin/split/storage/ExposedSplitFlowStateRepositorySpec.kt`

**Interfaces:**
- Produces (used by Task 2): `split.storage.SplitFlowStateType` (enum `DRAFT`, `SPLIT`), `split.storage.SplitFlowStateRow` (data class — see below), `split.storage.SplitFlowStateRepository` (interface: `upsert`, `findByChatAndMessage`, `listByChat`, `delete`, `listSplitsByGroup`), `split.storage.ExposedSplitFlowStateRepository(db: Database)`.

This task is fully self-contained — it doesn't touch `telegram` at all, and is testable purely against the DB.

- [ ] **Step 1: Add the migration**

Create `storage/src/main/resources/db/migration/V3__add_split_flow_state.sql`:

```sql
CREATE TABLE split_flow_state (
    chat_id                    INTEGER NOT NULL,
    prompt_message_id          INTEGER NOT NULL,
    group_id                   TEXT NOT NULL REFERENCES group_(id),
    state_type                 TEXT NOT NULL,
    invoker_id                 TEXT NOT NULL REFERENCES member(id),
    description                TEXT,
    amount_cents               INTEGER,
    currency                   TEXT,
    awaiting                   TEXT,
    split_type_hint            TEXT,
    mention_usernames          TEXT,
    exact_amounts              TEXT,
    stage                      TEXT,
    participant_ids            TEXT,
    amounts_entered            TEXT,
    actions_message_id         INTEGER,
    pending_participant_id     TEXT,
    pending_prompt_message_id  INTEGER,
    pending_is_auto_advance    INTEGER,
    PRIMARY KEY (chat_id, prompt_message_id)
);

CREATE INDEX split_flow_state_group_idx ON split_flow_state(group_id, state_type);
```

- [ ] **Step 2: Add the row type and repository port**

Create `storage/src/main/kotlin/split/storage/SplitFlowState.kt`:

```kotlin
package split.storage

import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

enum class SplitFlowStateType { DRAFT, SPLIT }

/**
 * One row per in-flight `/split` interaction (the draft wizard or the mode-choice/amount-entry
 * flow), keyed by (chatId, promptMessageId) — that pair never changes across a flow's lifetime,
 * so it doubles as the flow's stable identity for message-id matching. Fields only relevant to
 * one of DRAFT/SPLIT are null for the other; see the design spec's "Durable split-flow state"
 * section for why this is one table rather than one-per-variant or normalized further.
 */
data class SplitFlowStateRow(
    val chatId: Long,
    val groupId: GroupId,
    val stateType: SplitFlowStateType,
    val invokerId: MemberId,
    val promptMessageId: Long,
    val description: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val awaiting: String? = null,
    val splitTypeHint: String? = null,
    val mentionUsernames: List<String>? = null,
    val exactAmounts: List<BigDecimal>? = null,
    val stage: String? = null,
    val participantIds: List<MemberId>? = null,
    val amountsEntered: Map<MemberId, BigDecimal>? = null,
    val actionsMessageId: Long? = null,
    val pendingParticipantId: MemberId? = null,
    val pendingPromptMessageId: Long? = null,
    val pendingIsAutoAdvance: Boolean? = null,
)

interface SplitFlowStateRepository {
    suspend fun upsert(row: SplitFlowStateRow)

    /** Matches a row whose chatId is [chatId] and whose promptMessageId, actionsMessageId, or
     * pendingPromptMessageId equals [messageId] — the three message ids a flow's own messages
     * can carry. */
    suspend fun findByChatAndMessage(chatId: Long, messageId: Long): SplitFlowStateRow?

    /** Every row (any state type, any stage) currently open in [chatId]. */
    suspend fun listByChat(chatId: Long): List<SplitFlowStateRow>

    suspend fun delete(chatId: Long, promptMessageId: Long)

    /** SPLIT-type rows for [groupId], any stage. */
    suspend fun listSplitsByGroup(groupId: GroupId): List<SplitFlowStateRow>
}
```

- [ ] **Step 3: Add JSON column helpers**

`participant_ids`, `amounts_entered`, `mention_usernames`, and `exact_amounts` are stored as JSON
text — nothing ever queries them independently of the whole flow (see the design spec), so a
child table the way `ExpenseShareTable` normalizes `Expense`'s shares wouldn't earn its keep here.

Create `storage/src/main/kotlin/split/storage/JsonColumns.kt`:

```kotlin
package split.storage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun encodeStrings(values: List<String>): String =
    Json.encodeToString(ListSerializer(String.serializer()), values)

internal fun decodeStrings(text: String): List<String> =
    Json.decodeFromString(ListSerializer(String.serializer()), text)

internal fun encodeLongs(values: List<Long>): String =
    Json.encodeToString(ListSerializer(Long.serializer()), values)

internal fun decodeLongs(text: String): List<Long> =
    Json.decodeFromString(ListSerializer(Long.serializer()), text)

internal fun encodeAmountsByMemberId(values: Map<String, Long>): String =
    Json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), values)

internal fun decodeAmountsByMemberId(text: String): Map<String, Long> =
    Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), text)
```

- [ ] **Step 4: Add the dependency needed for Step 3**

Modify `storage/build.gradle.kts` — add one line to the `dependencies` block, right after the
existing coroutines line:

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
```

No `kotlin("plugin.serialization")` plugin needed here (unlike `telegram/build.gradle.kts`) —
`JsonColumns.kt` only serializes standard library collection types via explicit `Serializer`
instances, it doesn't declare new `@Serializable` classes.

- [ ] **Step 5: Add the Exposed table**

Modify `storage/src/main/kotlin/split/storage/Tables.kt` — append at the end of the file:

```kotlin

object SplitFlowStateTable : Table("split_flow_state") {
    val chatId = long("chat_id")
    val promptMessageId = long("prompt_message_id")
    val groupId = text("group_id").references(GroupTable.id)
    val stateType = text("state_type")
    val invokerId = text("invoker_id").references(MemberTable.id)
    val description = text("description").nullable()
    val amountCents = long("amount_cents").nullable()
    val currency = text("currency").nullable()
    val awaiting = text("awaiting").nullable()
    val splitTypeHint = text("split_type_hint").nullable()
    val mentionUsernames = text("mention_usernames").nullable()
    val exactAmounts = text("exact_amounts").nullable()
    val stage = text("stage").nullable()
    val participantIds = text("participant_ids").nullable()
    val amountsEntered = text("amounts_entered").nullable()
    val actionsMessageId = long("actions_message_id").nullable()
    val pendingParticipantId = text("pending_participant_id").nullable()
    val pendingPromptMessageId = long("pending_prompt_message_id").nullable()
    val pendingIsAutoAdvance = bool("pending_is_auto_advance").nullable()
    override val primaryKey = PrimaryKey(chatId, promptMessageId)
}
```

- [ ] **Step 6: Implement the Exposed repository**

Create `storage/src/main/kotlin/split/storage/ExposedSplitFlowStateRepository.kt`:

```kotlin
package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import split.core.GroupId
import split.core.MemberId

class ExposedSplitFlowStateRepository(private val db: Database) : SplitFlowStateRepository {

    override suspend fun upsert(row: SplitFlowStateRow): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            val updated = SplitFlowStateTable.update({
                (SplitFlowStateTable.chatId eq row.chatId) and (SplitFlowStateTable.promptMessageId eq row.promptMessageId)
            }) { it.fill(row) }
            if (updated == 0) {
                SplitFlowStateTable.insert { it.fill(row) }
            }
        }
    }

    override suspend fun findByChatAndMessage(chatId: Long, messageId: Long): SplitFlowStateRow? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                SplitFlowStateTable.selectAll()
                    .where {
                        (SplitFlowStateTable.chatId eq chatId) and
                            (
                                (SplitFlowStateTable.promptMessageId eq messageId) or
                                    (SplitFlowStateTable.actionsMessageId eq messageId) or
                                    (SplitFlowStateTable.pendingPromptMessageId eq messageId)
                            )
                    }
                    .singleOrNull()
                    ?.toRow()
            }
        }

    override suspend fun listByChat(chatId: Long): List<SplitFlowStateRow> = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            SplitFlowStateTable.selectAll()
                .where { SplitFlowStateTable.chatId eq chatId }
                .map { it.toRow() }
        }
    }

    override suspend fun delete(chatId: Long, promptMessageId: Long): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            SplitFlowStateTable.deleteWhere {
                (SplitFlowStateTable.chatId eq chatId) and (SplitFlowStateTable.promptMessageId eq promptMessageId)
            }
        }
    }

    override suspend fun listSplitsByGroup(groupId: GroupId): List<SplitFlowStateRow> = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            SplitFlowStateTable.selectAll()
                .where {
                    (SplitFlowStateTable.groupId eq groupId.value) and
                        (SplitFlowStateTable.stateType eq SplitFlowStateType.SPLIT.name)
                }
                .map { it.toRow() }
        }
    }
}

private fun UpdateBuilder<Int>.fill(row: SplitFlowStateRow) {
    this[SplitFlowStateTable.chatId] = row.chatId
    this[SplitFlowStateTable.promptMessageId] = row.promptMessageId
    this[SplitFlowStateTable.groupId] = row.groupId.value
    this[SplitFlowStateTable.stateType] = row.stateType.name
    this[SplitFlowStateTable.invokerId] = row.invokerId.value
    this[SplitFlowStateTable.description] = row.description
    this[SplitFlowStateTable.amountCents] = row.amount?.toCents()
    this[SplitFlowStateTable.currency] = row.currency
    this[SplitFlowStateTable.awaiting] = row.awaiting
    this[SplitFlowStateTable.splitTypeHint] = row.splitTypeHint
    this[SplitFlowStateTable.mentionUsernames] = row.mentionUsernames?.let(::encodeStrings)
    this[SplitFlowStateTable.exactAmounts] = row.exactAmounts?.let { amounts -> encodeLongs(amounts.map { it.toCents() }) }
    this[SplitFlowStateTable.stage] = row.stage
    this[SplitFlowStateTable.participantIds] = row.participantIds?.let { ids -> encodeStrings(ids.map { it.value }) }
    this[SplitFlowStateTable.amountsEntered] =
        row.amountsEntered?.let { map -> encodeAmountsByMemberId(map.entries.associate { it.key.value to it.value.toCents() }) }
    this[SplitFlowStateTable.actionsMessageId] = row.actionsMessageId
    this[SplitFlowStateTable.pendingParticipantId] = row.pendingParticipantId?.value
    this[SplitFlowStateTable.pendingPromptMessageId] = row.pendingPromptMessageId
    this[SplitFlowStateTable.pendingIsAutoAdvance] = row.pendingIsAutoAdvance
}

private fun ResultRow.toRow(): SplitFlowStateRow = SplitFlowStateRow(
    chatId = this[SplitFlowStateTable.chatId],
    groupId = GroupId(this[SplitFlowStateTable.groupId]),
    stateType = SplitFlowStateType.valueOf(this[SplitFlowStateTable.stateType]),
    invokerId = MemberId(this[SplitFlowStateTable.invokerId]),
    promptMessageId = this[SplitFlowStateTable.promptMessageId],
    description = this[SplitFlowStateTable.description],
    amount = this[SplitFlowStateTable.amountCents]?.centsToAmount(),
    currency = this[SplitFlowStateTable.currency],
    awaiting = this[SplitFlowStateTable.awaiting],
    splitTypeHint = this[SplitFlowStateTable.splitTypeHint],
    mentionUsernames = this[SplitFlowStateTable.mentionUsernames]?.let(::decodeStrings),
    exactAmounts = this[SplitFlowStateTable.exactAmounts]?.let { text -> decodeLongs(text).map { it.centsToAmount() } },
    stage = this[SplitFlowStateTable.stage],
    participantIds = this[SplitFlowStateTable.participantIds]?.let { text -> decodeStrings(text).map { MemberId(it) } },
    amountsEntered = this[SplitFlowStateTable.amountsEntered]?.let { text ->
        decodeAmountsByMemberId(text).entries.associate { MemberId(it.key) to it.value.centsToAmount() }
    },
    actionsMessageId = this[SplitFlowStateTable.actionsMessageId],
    pendingParticipantId = this[SplitFlowStateTable.pendingParticipantId]?.let { MemberId(it) },
    pendingPromptMessageId = this[SplitFlowStateTable.pendingPromptMessageId],
    pendingIsAutoAdvance = this[SplitFlowStateTable.pendingIsAutoAdvance],
)
```

`toCents()`/`centsToAmount()` are the existing `internal` extensions in `storage/src/main/kotlin/split/storage/Money.kt` — visible here since this file is in the same `split.storage` package, no import needed.

- [ ] **Step 7: Write the repository spec**

Create `storage/src/test/kotlin/split/storage/ExposedSplitFlowStateRepositorySpec.kt`:

```kotlin
package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import java.math.BigDecimal
import java.time.Instant

class ExposedSplitFlowStateRepositorySpec : StringSpec({

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

    fun aSplitRow(chatId: Long = -100, promptMessageId: Long = 1) = SplitFlowStateRow(
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

    fun aDraftRow(chatId: Long = -100, promptMessageId: Long = 1) = SplitFlowStateRow(
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

            repo.upsert(aSplitRow().copy(amountsEntered = mapOf(alice to BigDecimal("50.00"), bob to BigDecimal("40.00"))))

            repo.listByChat(-100) shouldBe listOf(
                aSplitRow().copy(amountsEntered = mapOf(alice to BigDecimal("50.00"), bob to BigDecimal("40.00"))),
            )
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
```

- [ ] **Step 8: Run the new spec and the full storage suite**

Run: `./gradlew :storage:test --tests "split.storage.ExposedSplitFlowStateRepositorySpec"`
Expected: all 8 cases PASS.

Run: `./gradlew :storage:test`
Expected: full `storage` module suite PASSES (nothing else changed).

- [ ] **Step 9: Commit**

```bash
git add storage/
git commit -m "Add split_flow_state storage layer for durable in-flight split state"
```

---

## Task 2: Migrate `SplitStateStore` to DB-backed, multi-flow-capable storage

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/SplitStateStore.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/CommandRouter.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowReplyHandler.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/SplitDraftReplyHandler.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/BotApplication.kt`
- Modify (test fixtures): `SplitFlowSpec.kt`, `SplitFlowCallbackHandlerSpec.kt`, `SplitFlowReplyHandlerSpec.kt`, `SplitDraftReplyHandlerSpec.kt`, `SplitExpenseCommandSpec.kt`
- Rewrite: `telegram/src/test/kotlin/split/telegram/SplitStateStoreSpec.kt`

**Interfaces:**
- Consumes (from Task 1): `split.storage.SplitFlowStateRepository`, `SplitFlowStateRow`, `SplitFlowStateType`, `split.storage.ExposedSplitFlowStateRepository(db)`, `split.storage.connectDatabase(path)`.
- Produces (used by Tasks 3 & 4): `SplitStateStore(repository: SplitFlowStateRepository)` with `suspend fun find(chatId: Long, messageId: Long): SplitState?`, `suspend fun set(chatId: Long, state: SplitState)`, `suspend fun clear(chatId: Long, state: SplitState)`, `suspend fun listAll(chatId: Long): List<SplitState>`, `suspend fun listOpenSplits(groupId: GroupId): List<PendingSplit>`. `SplitState` gains `val promptMessageId: Long`.

This task is a **behavior-preserving migration for every single-flow scenario** — authorization
stays exactly as strict as today (only Task 3 relaxes it), and every handler's logic is otherwise
untouched. The one behavior that *does* change, unavoidably (it's the point of the new composite
key), is that a chat can now hold more than one flow at once. `SplitFlowSpec.kt` has an existing
test, "a second /split replaces the first, still-pending flow", that specifically asserts the
*old* singleton behavior (tapping the first flow's stale message id is silently rejected because
starting a second `/split` overwrote it) — that assertion is now wrong and must be rewritten, not
just left passing. Because `SplitStateStore`'s public shape changes, the whole `telegram` module
only compiles once every step below is done — there's no meaningful intermediate commit, so this
whole task lands as one.

- [ ] **Step 1: Rewrite `SplitStateStore.kt`**

Replace the full contents of `telegram/src/main/kotlin/split/telegram/SplitStateStore.kt`:

```kotlin
package split.telegram

import split.core.GroupId
import split.core.MemberId
import split.core.SplitType
import split.storage.SplitFlowStateRepository
import split.storage.SplitFlowStateRow
import split.storage.SplitFlowStateType
import java.math.BigDecimal

sealed interface SplitState {
    val promptMessageId: Long
}

enum class SplitDraftField { DESCRIPTION, AMOUNT, PARTICIPANTS }

data class PendingSplitDraft(
    val invokerId: MemberId,
    val groupId: GroupId,
    val splitTypeHint: SplitType?,
    val description: String?,
    val amount: BigDecimal?,
    val currency: String,
    val mentionUsernames: List<String>,
    val exactAmounts: List<BigDecimal>?,
    val awaiting: SplitDraftField,
    override val promptMessageId: Long,
) : SplitState

enum class SplitFlowStage { CHOOSING_MODE, ENTERING_AMOUNTS }

data class PendingSplit(
    val invokerId: MemberId,
    val groupId: GroupId,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val participantIds: List<MemberId>,
    override val promptMessageId: Long,
    val stage: SplitFlowStage,
    val actionsMessageId: Long? = null,
    val amountsEntered: Map<MemberId, BigDecimal> = emptyMap(),
    val pendingParticipantId: MemberId? = null,
    val pendingPromptMessageId: Long? = null,
    val pendingIsAutoAdvance: Boolean = true,
) : SplitState

class SplitStateStore(private val repository: SplitFlowStateRepository) {

    suspend fun find(chatId: Long, messageId: Long): SplitState? =
        repository.findByChatAndMessage(chatId, messageId)?.toSplitState()

    suspend fun set(chatId: Long, state: SplitState) {
        repository.upsert(state.toRow(chatId))
    }

    suspend fun clear(chatId: Long, state: SplitState) {
        repository.delete(chatId, state.promptMessageId)
    }

    /** Every flow/draft currently open in [chatId] — used where a caller doesn't already know
     * which message id to look up by (test introspection today; nothing in production code
     * needs this, since a real update always carries a concrete message id). */
    suspend fun listAll(chatId: Long): List<SplitState> =
        repository.listByChat(chatId).map { it.toSplitState() }

    suspend fun listOpenSplits(groupId: GroupId): List<PendingSplit> =
        repository.listSplitsByGroup(groupId)
            .map { it.toSplitState() as PendingSplit }
            .filter { it.stage == SplitFlowStage.ENTERING_AMOUNTS }
}

private fun SplitFlowStateRow.toSplitState(): SplitState = when (stateType) {
    SplitFlowStateType.DRAFT -> PendingSplitDraft(
        invokerId = invokerId,
        groupId = groupId,
        splitTypeHint = splitTypeHint?.let { SplitType.valueOf(it) },
        description = description,
        amount = amount,
        currency = currency ?: error("DRAFT row missing currency"),
        mentionUsernames = mentionUsernames ?: emptyList(),
        exactAmounts = exactAmounts,
        awaiting = SplitDraftField.valueOf(awaiting ?: error("DRAFT row missing awaiting")),
        promptMessageId = promptMessageId,
    )
    SplitFlowStateType.SPLIT -> PendingSplit(
        invokerId = invokerId,
        groupId = groupId,
        amount = amount ?: error("SPLIT row missing amount"),
        currency = currency ?: error("SPLIT row missing currency"),
        description = description ?: error("SPLIT row missing description"),
        participantIds = participantIds ?: emptyList(),
        promptMessageId = promptMessageId,
        stage = SplitFlowStage.valueOf(stage ?: error("SPLIT row missing stage")),
        actionsMessageId = actionsMessageId,
        amountsEntered = amountsEntered ?: emptyMap(),
        pendingParticipantId = pendingParticipantId,
        pendingPromptMessageId = pendingPromptMessageId,
        pendingIsAutoAdvance = pendingIsAutoAdvance ?: true,
    )
}

private fun SplitState.toRow(chatId: Long): SplitFlowStateRow = when (this) {
    is PendingSplitDraft -> SplitFlowStateRow(
        chatId = chatId,
        groupId = groupId,
        stateType = SplitFlowStateType.DRAFT,
        invokerId = invokerId,
        promptMessageId = promptMessageId,
        description = description,
        amount = amount,
        currency = currency,
        awaiting = awaiting.name,
        splitTypeHint = splitTypeHint?.name,
        mentionUsernames = mentionUsernames,
        exactAmounts = exactAmounts,
    )
    is PendingSplit -> SplitFlowStateRow(
        chatId = chatId,
        groupId = groupId,
        stateType = SplitFlowStateType.SPLIT,
        invokerId = invokerId,
        promptMessageId = promptMessageId,
        description = description,
        amount = amount,
        currency = currency,
        stage = stage.name,
        participantIds = participantIds,
        amountsEntered = amountsEntered,
        actionsMessageId = actionsMessageId,
        pendingParticipantId = pendingParticipantId,
        pendingPromptMessageId = pendingPromptMessageId,
        pendingIsAutoAdvance = pendingIsAutoAdvance,
    )
}
```

- [ ] **Step 2: Rewrite `SplitStateStoreSpec.kt`**

Replace the full contents of `telegram/src/test/kotlin/split/telegram/SplitStateStoreSpec.kt`:

```kotlin
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
            store.listAll(-100) shouldBe listOf(aFlow(promptMessageId = 1).copy(stage = SplitFlowStage.ENTERING_AMOUNTS))
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
```

- [ ] **Step 3: `CommandRouter.kt` — `isTrackedReply` becomes `suspend`**

Modify `telegram/src/main/kotlin/split/telegram/CommandRouter.kt` (the constructor parameter):

```kotlin
    private val isTrackedReply: suspend (chatId: Long, messageId: Long) -> Boolean = { _, _ -> false },
```

(No other change in this file — `handleReply` already calls it from a suspend context, and
`{ _, _ -> false }` / `{ _, _ -> true }` lambda literals at existing call sites adapt to the
`suspend` function type automatically.)

- [ ] **Step 4: `SplitFlowReplyHandler.kt`**

Modify the first lines of `handle`:

```kotlin
    suspend fun handle(context: ReplyContext) {
        val flow = splitStateStore.find(context.chatId, context.replyToMessageId) as? PendingSplit ?: return
        if (flow.stage != SplitFlowStage.ENTERING_AMOUNTS) return
        if (context.replyToMessageId != flow.pendingPromptMessageId) return
        if (context.memberId != flow.invokerId) return
        val pendingParticipantId = flow.pendingParticipantId ?: return
```

(Everything after this line is unchanged. This keeps today's invoker-only behavior — Task 3
relaxes it.)

- [ ] **Step 5: `SplitFlowCallbackHandler.kt` — top of `handle`**

Modify the start of `handle` (through the invoker check, before the `when`):

```kotlin
    suspend fun handle(context: CallbackContext) {
        val flow = splitStateStore.find(context.chatId, context.messageId) as? PendingSplit
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.memberId != flow.invokerId) {
            telegramApi.answerCallbackQuery(
                context.callbackQueryId,
                "Only the person who started this split can do that.",
                showAlert = true,
            )
            return
        }

        when {
```

(This replaces the old two-step "flow == null" then "messageId doesn't match either tracked id"
check with one `find` call — `find` already matches by `promptMessageId`/`actionsMessageId`, so
the manual message-id comparison is gone. The `when` block and every handler function below it
are unchanged in this task.)

Then update the three `clear` calls in this file to pass the flow instance — in `chooseEqual`,
`cancel`, and `confirm`, change:

```kotlin
        splitStateStore.clear(context.chatId)
```

to:

```kotlin
        splitStateStore.clear(context.chatId, flow)
```

(`chooseExact` and `pickParticipant` have no `clear` call — unchanged.)

- [ ] **Step 6: `SplitDraftReplyHandler.kt`**

Modify the first lines of `handle`:

```kotlin
    suspend fun handle(context: ReplyContext) {
        val draft = splitStateStore.find(context.chatId, context.replyToMessageId) as? PendingSplitDraft ?: return
        if (context.memberId != draft.invokerId) return
```

(The old `if (context.replyToMessageId != draft.promptMessageId) return` line is deleted —
`find` already matched on that message id, since a `PendingSplitDraft`'s only tracked id is its
own `promptMessageId`.)

In `advance()`, change:

```kotlin
        splitStateStore.clear(context.chatId)
```

to:

```kotlin
        splitStateStore.clear(context.chatId, updated)
```

- [ ] **Step 7: `BotApplication.kt` wiring**

Add the import:

```kotlin
import split.storage.ExposedSplitFlowStateRepository
```

Change the store construction:

```kotlin
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
```

Change the router's `replyHandler` and `isTrackedReply`:

```kotlin
            replyHandler = { context ->
                when (splitStateStore.find(context.chatId, context.replyToMessageId)) {
                    is PendingSplitDraft -> splitDraftReplyHandler.handle(context)
                    is PendingSplit -> splitFlowReplyHandler.handle(context)
                    null -> {}
                }
            },
            isTrackedReply = { chatId, messageId ->
                splitStateStore.find(chatId, messageId) != null
            },
```

- [ ] **Step 8: Update the five test fixtures' `SplitStateStore()` construction**

In each of these files, the fixture class already has a `db: Database` constructor parameter.
Change the line `val splitStateStore = SplitStateStore()` to
`val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))`, and add
`import split.storage.ExposedSplitFlowStateRepository` alongside the file's other
`split.storage.*` imports:

- `telegram/src/test/kotlin/split/telegram/SplitFlowSpec.kt` (`FlowFixture`)
- `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt` (`CallbackFixture`)
- `telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt` (`ReplyFixture`)
- `telegram/src/test/kotlin/split/telegram/SplitDraftReplyHandlerSpec.kt` (`DraftFixture`)
- `telegram/src/test/kotlin/split/telegram/SplitExpenseCommandSpec.kt` (`CommandFixture`)

- [ ] **Step 9: Update each fixture's state-introspection helper**

These helpers currently call `splitStateStore.get(...)`, which no longer exists. Each one already
only reads state for its own fixture's single chat id in that test — `listAll` plus a filter
replaces `get` exactly, and (importantly) doesn't require already knowing a message id, unlike
`find`:

`SplitFlowSpec.kt`:
```kotlin
private suspend fun FlowFixture.currentFlow() =
    splitStateStore.listAll(CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()
```

`SplitFlowCallbackHandlerSpec.kt`:
```kotlin
private suspend fun CallbackFixture.setFlow(flow: PendingSplit) = splitStateStore.set(CALLBACK_CHAT_ID, flow)

private suspend fun CallbackFixture.currentFlow() =
    splitStateStore.listAll(CALLBACK_CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()
```

`SplitFlowReplyHandlerSpec.kt`:
```kotlin
private suspend fun ReplyFixture.setFlow(flow: PendingSplit) = splitStateStore.set(REPLY_CHAT_ID, flow)

private suspend fun ReplyFixture.currentFlow() = splitStateStore.listAll(REPLY_CHAT_ID).singleOrNull()
```

`SplitDraftReplyHandlerSpec.kt`:
```kotlin
private suspend fun DraftFixture.currentDraft() =
    splitStateStore.listAll(DRAFT_CHAT_ID).filterIsInstance<PendingSplitDraft>().singleOrNull()

private suspend fun DraftFixture.currentFlow() =
    splitStateStore.listAll(DRAFT_CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()
```

`SplitExpenseCommandSpec.kt`:
```kotlin
private suspend fun CommandFixture.currentState() = splitStateStore.listAll(COMMAND_CHAT_ID).singleOrNull()
```

These were previously non-`suspend` functions (`private fun ...`); every call site that used them
is already inside a `suspend`/`withTestDatabase` block, so adding `suspend` here needs no other
change at the call sites.

- [ ] **Step 10: `ExpensesCommandSpec.kt` doesn't construct `SplitStateStore` yet — skip it in this task**

`ExpensesCommand` doesn't take a `SplitStateStore` until Task 4; nothing here to change now.

- [ ] **Step 11: Replace the now-incorrect "second /split replaces the first" test in `SplitFlowSpec.kt`**

This existing test (around line 202) asserts the *old* singleton behavior: starting a second
`/split` overwrites the first, so tapping the first flow's stale message id is silently rejected.
That's no longer true — both flows now coexist, so tapping the first flow's (still valid) message
id must actually resolve it. Replace the whole test:

```kotlin
        "a second /split coexists with the first, still-pending flow" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobbyId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "30 coffee @bobby")
                val coffeePromptMessageId = (fixture.splitStateStore.listAll(CHAT_ID).single() as PendingSplit).promptMessageId

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val dinnerFlow = fixture.splitStateStore.listAll(CHAT_ID)
                    .filterIsInstance<PendingSplit>()
                    .single { it.promptMessageId != coffeePromptMessageId }
                dinnerFlow.description shouldBe "dinner"

                fixture.tapEqual(aliceId, groupId, coffeePromptMessageId)

                fixture.expenseCreatedWith(groupId, aliceId to BigDecimal("15.00"), bobbyId to BigDecimal("15.00"))
                (fixture.splitStateStore.find(CHAT_ID, dinnerFlow.promptMessageId) as PendingSplit).description shouldBe "dinner"
            }
        }

        "cancelling one of two concurrent flows leaves the other's amounts untouched" {
            withTestDatabase { db ->
                val fixture = FlowFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobbyInGroup()

                fixture.startSplit(aliceId, groupId, "30 coffee @bobby")
                val coffeePromptMessageId = (fixture.splitStateStore.listAll(CHAT_ID).single() as PendingSplit).promptMessageId
                fixture.tapExact(aliceId, groupId, coffeePromptMessageId)
                fixture.pick(participantIndex = 0, memberId = aliceId, groupId = groupId, messageId = coffeePromptMessageId)
                val coffeeAlicePromptId =
                    (fixture.splitStateStore.find(CHAT_ID, coffeePromptMessageId) as PendingSplit).pendingPromptMessageId!!
                fixture.replyWithAmount(aliceId, groupId, coffeeAlicePromptId, "15")

                fixture.startSplit(aliceId, groupId, "90 dinner @bobby")
                val dinnerPromptMessageId = fixture.splitStateStore.listAll(CHAT_ID)
                    .filterIsInstance<PendingSplit>()
                    .single { it.promptMessageId != coffeePromptMessageId }
                    .promptMessageId

                val coffeeActionsMessageId =
                    (fixture.splitStateStore.find(CHAT_ID, coffeePromptMessageId) as PendingSplit).actionsMessageId!!
                fixture.callbackHandler.handle(
                    CallbackContext(CHAT_ID, aliceId, groupId, "cbq", coffeeActionsMessageId, SPLIT_CANCEL_DATA),
                )

                fixture.splitStateStore.find(CHAT_ID, coffeePromptMessageId) shouldBe null
                (fixture.splitStateStore.find(CHAT_ID, dinnerPromptMessageId) as PendingSplit).amountsEntered shouldBe emptyMap()
            }
        }
```

(`splitStateStore` is already a public `val` on `FlowFixture`, and `CHAT_ID` is the file's
existing private top-level constant — both accessible directly from test bodies in this file.)

- [ ] **Step 12: Compile and run the full `telegram` suite**

Run: `./gradlew :telegram:test`
Expected: every spec PASSES, including the rewritten `SplitStateStoreSpec`, the two new/rewritten
`SplitFlowSpec` cases, and the other four fixtures' full existing test bodies (genuinely unchanged
behavior for all of those — only `SplitFlowSpec.kt`'s multi-flow test needed rewriting).

- [ ] **Step 13: Run the full build (both modules + ktlint/detekt)**

Run: `./gradlew check`
Expected: PASSES. Fix any ktlint/detekt findings inline (the baselines are pre-existing, not a
license to add new violations).

- [ ] **Step 14: Commit**

```bash
git add telegram/
git commit -m "Back SplitStateStore with SQLite, allowing multiple flows per chat"
```

---

## Task 3: Let a participant answer or correct their own amount

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowReplyHandler.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`

**Interfaces:**
- Consumes: everything from Task 2 (`SplitStateStore.find/set/clear`, `PendingSplit`).
- Produces (used by Task 4): `SplitFlowCallbackHandler`'s private `assignPendingParticipant(context, flow, memberId)` — jumps the flow's pending slot to `memberId` and sends them a fresh prompt. Task 4's "enter your amount" button reuses this directly.

- [ ] **Step 1: Write the failing reply-handler test**

Add to `telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt` (inside the
`StringSpec { ... }` block, alongside the existing cases):

```kotlin
        "a named participant who isn't the invoker can answer their own auto-advanced prompt" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow = PendingSplit(
                    invokerId = aliceId,
                    groupId = groupId,
                    amount = BigDecimal("90.00"),
                    currency = "USD",
                    description = "dinner",
                    participantIds = listOf(aliceId, bobId),
                    promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS,
                    actionsMessageId = 2,
                    pendingParticipantId = bobId,
                    pendingPromptMessageId = 3,
                )
                fixture.setFlow(flow)

                fixture.handler.handle(ReplyContext(REPLY_CHAT_ID, bobId, groupId, 3, "45.00"))

                (fixture.currentFlow() as PendingSplit).amountsEntered shouldBe mapOf(bobId to BigDecimal("45.00"))
            }
        }

        "a reply from someone who is neither the invoker nor the pending participant is ignored" {
            withTestDatabase { db ->
                val fixture = ReplyFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val carolId = fixture.resolver.resolveMember("3", "carol", "Carol")
                fixture.resolver.ensureGroupMembership(groupId, carolId)
                val flow = PendingSplit(
                    invokerId = aliceId,
                    groupId = groupId,
                    amount = BigDecimal("90.00"),
                    currency = "USD",
                    description = "dinner",
                    participantIds = listOf(aliceId, bobId, carolId),
                    promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS,
                    actionsMessageId = 2,
                    pendingParticipantId = bobId,
                    pendingPromptMessageId = 3,
                )
                fixture.setFlow(flow)

                fixture.handler.handle(ReplyContext(REPLY_CHAT_ID, carolId, groupId, 3, "45.00"))

                (fixture.currentFlow() as PendingSplit).amountsEntered shouldBe emptyMap()
            }
        }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowReplyHandlerSpec"`
Expected: the first new case FAILS (`amountsEntered` is still empty — Bob's reply is currently
rejected because he isn't the invoker). The second new case already PASSES (Carol was never
allowed either, before or after) — that's fine, it's here to guard against a regression once the
check is widened.

- [ ] **Step 3: Widen the reply-handler authorization check**

Modify `SplitFlowReplyHandler.kt`'s `handle`, reordering the invoker check to come after resolving
`pendingParticipantId`, and widening it:

```kotlin
    suspend fun handle(context: ReplyContext) {
        val flow = splitStateStore.find(context.chatId, context.replyToMessageId) as? PendingSplit ?: return
        if (flow.stage != SplitFlowStage.ENTERING_AMOUNTS) return
        if (context.replyToMessageId != flow.pendingPromptMessageId) return
        val pendingParticipantId = flow.pendingParticipantId ?: return
        if (context.memberId != flow.invokerId && context.memberId != pendingParticipantId) return
```

(Everything after this is unchanged.)

- [ ] **Step 4: Run the reply-handler spec again**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowReplyHandlerSpec"`
Expected: all cases, including both new ones, PASS.

- [ ] **Step 5: Write the failing callback-handler tests**

Add to `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`:

```kotlin
        "a participant can pick their own row to answer out of turn, before auto-advance reaches them" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow = fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                    .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 3)
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(1)))

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.pendingIsAutoAdvance shouldBe false
            }
        }

        "a participant picking someone else's row is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow = fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                    .copy(pendingParticipantId = aliceId, pendingPromptMessageId = 3)
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(0)))

                fixture.currentFlow()!!.pendingParticipantId shouldBe aliceId
                fixture.telegramApi.answeredCallbacks.last().second shouldBe
                    "Only the person who started this split, or that participant, can do that."
            }
        }

        "mode choice, cancel, and confirm still reject a non-invoker" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.aChoosingModeFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, SPLIT_MODE_EQUAL_DATA),
                )

                fixture.expenseRepository.listActive(groupId) shouldBe emptyList()
                fixture.telegramApi.answeredCallbacks.last().second shouldBe
                    "Only the person who started this split can do that."
            }
        }

        "a participant can pick their own row again after auto-advance has moved past them, to correct it" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val flow = fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)).copy(
                    amountsEntered = mapOf(bobId to BigDecimal("40.00")),
                    pendingParticipantId = null,
                    pendingPromptMessageId = null,
                )
                fixture.setFlow(flow)

                fixture.handler.handle(CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 1, splitPickData(1)))

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.amountsEntered shouldBe mapOf(bobId to BigDecimal("40.00"))
            }
        }
```

- [ ] **Step 6: Run it to see the first two fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: the first and fourth new cases FAIL (today's blanket invoker-only check rejects Bob
entirely, before `pickParticipant` even runs). The second and third PASS already (unchanged
behavior) — they're here to lock in that picking *someone else's* row, and mode-choice/cancel/
confirm, all remain invoker-only after this task's change.

- [ ] **Step 7: Relax `pickParticipant`, keep everything else invoker-only**

Modify `SplitFlowCallbackHandler.kt` — remove the blanket invoker check from the top of `handle`
(it moves into each individual action instead), and extract a small `requireInvoker` helper:

```kotlin
    suspend fun handle(context: CallbackContext) {
        val flow = splitStateStore.find(context.chatId, context.messageId) as? PendingSplit
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }

        when {
            context.data == SPLIT_MODE_EQUAL_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseEqual(context, flow)
            context.data == SPLIT_MODE_EXACT_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseExact(context, flow)
            context.data.startsWith(SPLIT_PICK_PREFIX) && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> pickParticipant(context, flow)
            context.data == SPLIT_CANCEL_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> cancel(context, flow)
            context.data == SPLIT_CONFIRM_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> confirm(context, flow)
            else -> telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
        }
    }

    private suspend fun requireInvoker(context: CallbackContext, flow: PendingSplit): Boolean {
        if (context.memberId == flow.invokerId) return true
        telegramApi.answerCallbackQuery(
            context.callbackQueryId,
            "Only the person who started this split can do that.",
            showAlert = true,
        )
        return false
    }
```

Add `if (!requireInvoker(context, flow)) return` as the first line of `chooseEqual`, `chooseExact`,
`cancel`, and `confirm` (each already takes `(context: CallbackContext, flow: PendingSplit)`, so
this is a one-line insertion at the top of each function body — nothing else in those four
functions changes).

Replace `pickParticipant` and extract the shared assignment logic:

```kotlin
    private suspend fun pickParticipant(context: CallbackContext, flow: PendingSplit) {
        val index = context.data.removePrefix(SPLIT_PICK_PREFIX).toIntOrNull()
        val memberId = index?.let { flow.participantIds.getOrNull(it) }
        if (memberId == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.memberId != flow.invokerId && context.memberId != memberId) {
            telegramApi.answerCallbackQuery(
                context.callbackQueryId,
                "Only the person who started this split, or that participant, can do that.",
                showAlert = true,
            )
            return
        }
        assignPendingParticipant(context, flow, memberId)
    }

    private suspend fun assignPendingParticipant(context: CallbackContext, flow: PendingSplit, memberId: MemberId) {
        flow.pendingPromptMessageId?.let { telegramApi.deleteMessage(context.chatId, it) }
        val (members, usernames) = membersAndUsernames(flow)
        val promptMessageId = sendParticipantAmountPrompt(context.chatId, memberId, members, usernames, telegramApi)
        splitStateStore.set(
            context.chatId,
            flow.copy(pendingParticipantId = memberId, pendingPromptMessageId = promptMessageId, pendingIsAutoAdvance = false),
        )
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }
```

Add `import split.core.MemberId` to this file if it isn't already imported (check the existing
import list first — `SplitFlowStarter.kt` already imports it, but this file may not).

- [ ] **Step 8: Run the callback-handler spec again**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: all cases PASS, including the three new ones.

- [ ] **Step 9: Run the full `telegram` suite and `check`**

Run: `./gradlew :telegram:test && ./gradlew check`
Expected: PASSES.

- [ ] **Step 10: Commit**

```bash
git add telegram/
git commit -m "Let a participant answer or correct their own /split amount"
```

---

## Task 4: `/expenses pending`

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowFormatting.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/ExpensesCommand.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/BotApplication.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/SplitFlowFormattingSpec.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/ExpensesCommandSpec.kt`

**Interfaces:**
- Consumes: `SplitStateStore.listOpenSplits(groupId)` (Task 2), `assignPendingParticipant` (Task 3, same file).
- Produces: `PENDING_ENTER_PREFIX`, `pendingSplitEnterData(promptMessageId)`, `buildPendingSplitsMessage(flows, members, usernames)`, `pendingSplitsKeyboard(flows)` — all in `SplitFlowFormatting.kt`.

- [ ] **Step 1: Write the failing formatting tests**

Add to `telegram/src/test/kotlin/split/telegram/SplitFlowFormattingSpec.kt`:

```kotlin
        "buildPendingSplitsMessage lists each flow's description, amount, and per-participant status" {
            val flow = PendingSplit(
                invokerId = alice.id,
                groupId = split.core.GroupId("g1"),
                amount = BigDecimal("90.00"),
                currency = "USD",
                description = "dinner",
                participantIds = listOf(alice.id, bob.id),
                promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS,
                amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            )

            val message = buildPendingSplitsMessage(listOf(flow), listOf(alice, bob), emptyMap())

            val rows = (message.blocks.single() as RichBlockTable).cells
            rows shouldBe listOf(
                listOf(RichBlockTableCell("Split", isHeader = true), RichBlockTableCell("Status", isHeader = true)),
                listOf(
                    RichBlockTableCell("dinner\n90.00 USD"),
                    RichBlockTableCell("Alice 50.00 USD\nBob —"),
                ),
            )
        }

        "buildPendingSplitsMessage says so when there's nothing open" {
            val message = buildPendingSplitsMessage(emptyList(), emptyList(), emptyMap())

            message.blocks shouldBe listOf(RichBlockParagraph("No pending splits."))
        }

        "pendingSplitsKeyboard has one button per flow, encoding its prompt message id" {
            val dinner = PendingSplit(
                invokerId = alice.id,
                groupId = split.core.GroupId("g1"),
                amount = BigDecimal("90.00"),
                currency = "USD",
                description = "dinner",
                participantIds = listOf(alice.id, bob.id),
                promptMessageId = 7,
                stage = SplitFlowStage.ENTERING_AMOUNTS,
            )

            pendingSplitsKeyboard(listOf(dinner)) shouldBe
                InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(InlineKeyboardButton(text = "Enter your amount — dinner", callbackData = "pending:enter:7")),
                    ),
                )
        }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowFormattingSpec"`
Expected: compile failure (`buildPendingSplitsMessage`/`pendingSplitsKeyboard`/`PENDING_ENTER_PREFIX` don't exist yet).

- [ ] **Step 3: Implement the formatting functions**

Add to `telegram/src/main/kotlin/split/telegram/SplitFlowFormatting.kt`:

```kotlin
const val PENDING_ENTER_PREFIX = "pending:enter:"

fun pendingSplitEnterData(promptMessageId: Long): String = "$PENDING_ENTER_PREFIX$promptMessageId"

fun buildPendingSplitsMessage(
    flows: List<PendingSplit>,
    members: List<Member>,
    usernames: Map<MemberId, String>,
): InputRichMessage {
    if (flows.isEmpty()) {
        return InputRichMessage(blocks = listOf(RichBlockParagraph("No pending splits.")))
    }
    val nameOf = members.associateBy { it.id }
    val header = listOf("Split", "Status").map { RichBlockTableCell(text = it, isHeader = true) }
    val rows = flows.map { flow -> pendingSplitRow(flow, nameOf, usernames) }
    return InputRichMessage(blocks = listOf(RichBlockTable(cells = listOf(header) + rows)))
}

private fun pendingSplitRow(
    flow: PendingSplit,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
): List<RichBlockTableCell> {
    val summary = "${flow.description}\n${formatAmount(flow.amount, flow.currency)}"
    val status = flow.participantIds.joinToString("\n") { memberId ->
        val name = plainName(nameOf.getValue(memberId), usernames)
        val amount = flow.amountsEntered[memberId]?.let { formatAmount(it, flow.currency) } ?: "—"
        "$name $amount"
    }
    return listOf(summary, status).map { RichBlockTableCell(text = it) }
}

fun pendingSplitsKeyboard(flows: List<PendingSplit>): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard = flows.map { flow ->
            listOf(
                InlineKeyboardButton(
                    text = "Enter your amount — ${flow.description}",
                    callbackData = pendingSplitEnterData(flow.promptMessageId),
                ),
            )
        },
    )
```

- [ ] **Step 4: Run the formatting spec again**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowFormattingSpec"`
Expected: all cases, including the three new ones, PASS.

- [ ] **Step 5: Write the failing callback-handler tests for entering from the list**

Add to `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`:

```kotlin
        "entering from the pending list sends a fresh prompt to that participant" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                fixture.telegramApi.sentForceReplyPrompts.isNotEmpty() shouldBe true
            }
        }

        "entering from the pending list to correct an already-submitted amount" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                fixture.setFlow(
                    fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId))
                        .copy(amountsEntered = mapOf(bobId to BigDecimal("40.00"))),
                )

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                val updated = fixture.currentFlow()!!
                updated.pendingParticipantId shouldBe bobId
                updated.amountsEntered shouldBe mapOf(bobId to BigDecimal("40.00"))
            }
        }

        "entering from the pending list for a non-participant is rejected" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                val carolId = fixture.resolver.resolveMember("3", "carol", "Carol")
                fixture.setFlow(fixture.anEnteringAmountsFlow(aliceId, groupId, listOf(aliceId, bobId)))

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, carolId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                fixture.currentFlow()!!.pendingParticipantId shouldBe null
            }
        }

        "entering from the pending list for a flow already closed by someone else shows it's no longer active" {
            withTestDatabase { db ->
                val fixture = CallbackFixture(db)
                val (aliceId, bobId, groupId) = fixture.aliceAndBobInGroup()
                // No flow set for prompt message id 1 — it was already confirmed/cancelled.

                fixture.handler.handle(
                    CallbackContext(CALLBACK_CHAT_ID, bobId, groupId, "cbq", 999, pendingSplitEnterData(1)),
                )

                fixture.telegramApi.answeredCallbacks.last() shouldBe Triple("cbq", "This split is no longer active.", true)
            }
        }
```

- [ ] **Step 6: Run it to see it fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: the first two new cases (fresh prompt for an unanswered participant, and correcting an
already-submitted amount) FAIL — today's `handle` treats `context.messageId` (999, the
pending-list message, not any flow's tracked id) as the lookup key, so `find` returns null and
every one of these callbacks falls into the generic "no longer active" branch regardless of
`callback_data`. The third and fourth (non-participant, already-closed flow) already PASS
coincidentally (same reason) — they stay as locked-in regression guards once real routing exists.

- [ ] **Step 7: Route the new prefix before the normal message-id lookup**

Modify `SplitFlowCallbackHandler.kt`'s `handle` — check the new prefix first, since (unlike every
other action here) this one's flow isn't found via `context.messageId`:

```kotlin
    suspend fun handle(context: CallbackContext) {
        if (context.data.startsWith(PENDING_ENTER_PREFIX)) {
            enterFromPendingList(context)
            return
        }
        val flow = splitStateStore.find(context.chatId, context.messageId) as? PendingSplit
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        // ... rest of handle unchanged (the `when` block from Task 3) ...
```

Add the new handler, next to `pickParticipant`:

```kotlin
    private suspend fun enterFromPendingList(context: CallbackContext) {
        val promptMessageId = context.data.removePrefix(PENDING_ENTER_PREFIX).toLongOrNull()
        val flow = promptMessageId?.let { splitStateStore.find(context.chatId, it) } as? PendingSplit
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.memberId !in flow.participantIds) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "You're not part of this split.", showAlert = true)
            return
        }
        assignPendingParticipant(context, flow, context.memberId)
    }
```

- [ ] **Step 8: Run the callback-handler spec again**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: all cases, including the three new ones, PASS.

- [ ] **Step 9: Write the failing `/expenses pending` tests**

Add to `telegram/src/test/kotlin/split/telegram/ExpensesCommandSpec.kt` (following the file's
existing style of building everything inline per test rather than via a shared fixture class):

```kotlin
        "expenses pending lists open splits for the group" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                val telegramApi = FakeTelegramApi()
                val command = ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)
                splitStateStore.set(
                    -100001,
                    PendingSplit(
                        invokerId = aliceId,
                        groupId = groupId,
                        amount = BigDecimal("90.00"),
                        currency = "USD",
                        description = "dinner",
                        participantIds = listOf(aliceId),
                        promptMessageId = 1,
                        stage = SplitFlowStage.ENTERING_AMOUNTS,
                    ),
                )

                command.handle(CommandContext(-100001, aliceId, "1", groupId, "pending"))

                val message = telegramApi.sentRichMessages.single().second
                (message.blocks.single() as RichBlockTable).cells.size shouldBe 2
            }
        }

        "expenses pending says so when nothing is open" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                val telegramApi = FakeTelegramApi()
                val command = ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)

                command.handle(CommandContext(-100001, aliceId, "1", groupId, "pending"))

                telegramApi.sentRichMessages.single().second.blocks shouldBe listOf(RichBlockParagraph("No pending splits."))
            }
        }

        "expenses pending drops a flow once it's been confirmed" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val expenseRepository = ExposedExpenseRepository(db)
                val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                val telegramApi = FakeTelegramApi()
                val command = ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi, splitStateStore)
                val callbackHandler =
                    SplitFlowCallbackHandler(splitStateStore, memberRepository, expenseRepository, platformDirectory, telegramApi)
                val flow = PendingSplit(
                    invokerId = aliceId,
                    groupId = groupId,
                    amount = BigDecimal("90.00"),
                    currency = "USD",
                    description = "dinner",
                    participantIds = listOf(aliceId),
                    promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS,
                    actionsMessageId = 2,
                    amountsEntered = mapOf(aliceId to BigDecimal("90.00")),
                )
                splitStateStore.set(-100001, flow)

                callbackHandler.handle(CallbackContext(-100001, aliceId, groupId, "cbq", 2, SPLIT_CONFIRM_DATA))
                command.handle(CommandContext(-100001, aliceId, "1", groupId, "pending"))

                telegramApi.sentRichMessages.last().second.blocks shouldBe listOf(RichBlockParagraph("No pending splits."))
            }
        }
```

Add `import split.storage.ExposedSplitFlowStateRepository` to this file's imports.

- [ ] **Step 10: Run it to see it fail**

Run: `./gradlew :telegram:test --tests "split.telegram.ExpensesCommandSpec"`
Expected: compile failure — `ExpensesCommand`'s constructor doesn't take a `SplitStateStore` yet,
and it doesn't branch on `"pending"`.

- [ ] **Step 11: Add the `pending` branch to `ExpensesCommand`**

Replace the full contents of `telegram/src/main/kotlin/split/telegram/ExpensesCommand.kt`:

```kotlin
package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory

class ExpensesCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
    private val splitStateStore: SplitStateStore,
) {
    suspend fun handle(context: CommandContext) {
        groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        if (context.args.trim() == "pending") {
            handlePending(context)
            return
        }

        val expenses =
            expenseRepository
                .listActive(context.groupId)
                .sortedBy { it.createdAt }
                .takeLast(10)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(context.chatId, buildExpenseListMessage(expenses, members, usernames))
    }

    private suspend fun handlePending(context: CommandContext) {
        val flows = splitStateStore.listOpenSplits(context.groupId)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(
            context.chatId,
            buildPendingSplitsMessage(flows, members, usernames),
            if (flows.isEmpty()) null else pendingSplitsKeyboard(flows),
        )
    }
}
```

- [ ] **Step 12: Wire the new dependency in `BotApplication.kt`**

Modify the `"expenses"` entry in the `handlers` map:

```kotlin
            "expenses" to ExpensesCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                platformDirectory,
                telegramApi,
                splitStateStore,
            )::handle,
```

- [ ] **Step 13: Run the expenses spec, then the full suite**

Run: `./gradlew :telegram:test --tests "split.telegram.ExpensesCommandSpec"`
Expected: all cases, including the three new ones, PASS.

Run: `./gradlew :telegram:test`
Expected: full `telegram` module suite PASSES.

- [ ] **Step 14: Run the full build**

Run: `./gradlew check`
Expected: PASSES.

- [ ] **Step 15: Manually verify against a real bot (optional but recommended)**

Following `README.md`'s "Running as a live/test bot" section: run the bot, in a group with two
accounts run `/split 90 dinner @other`, tap **Exact**, have the *other* account (not the invoker)
reply to their own amount prompt, confirm it's accepted; run `/expenses pending` mid-flow from a
third participant's account (or the same group) and tap "Enter your amount" to confirm it opens a
fresh, answerable prompt.

- [ ] **Step 16: Commit**

```bash
git add telegram/
git commit -m "Add /expenses pending, letting participants find and answer a flow without scrolling back"
```
